//! Backend dispatch: the protocol-agnostic card and addressbook API the
//! bridge exposes, each method routing to the CardDAV, Graph, JMAP or
//! Google implementation behind the account's base URL.

use io_webdav::rfc6578::sync_collection::SyncDelta;
use url::Url;

use crate::{
    account::{self, Backend},
    client::Client,
    types::{Addressbook, BridgeError, Card, CardDelta, Credentials, PushChange, PushOutcome},
};

/// Backend dispatch: the protocol-agnostic card and addressbook API the
/// bridge exposes, each method routing to the CardDAV, Graph, JMAP or
/// Google implementation behind the account's base URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the account's addressbooks, each carrying the absolute
    /// collection URL every card operation targets: the CardDAV
    /// discovery walk, the Graph contact folders, the JMAP
    /// AddressBooks or the Google contact groups. Doubles as the
    /// connection check during onboarding.
    pub fn list_addressbooks(
        &mut self,
        base_url: &str,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        match Backend::of(base_url) {
            Backend::Carddav => self.list_carddav_addressbooks(&parse_url(base_url)?, credentials),
            Backend::Graph => {
                let mut books = self.list_graph_addressbooks(credentials.password)?;
                for book in &mut books {
                    let segment = match book.id.is_empty() {
                        true => account::MSGRAPH_DEFAULT_FOLDER,
                        false => &book.id,
                    };
                    book.url = format!("{base_url}/{segment}");
                }
                Ok(books)
            }
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                let mut books = self.list_jmap_addressbooks(&session_url, credentials)?;
                for book in &mut books {
                    book.url = format!("{base_url}/{}", book.id);
                }
                Ok(books)
            }
            Backend::Google => {
                let mut books = self.list_google_addressbooks(credentials.password)?;
                for book in &mut books {
                    let segment = match book.id.is_empty() {
                        true => account::GOOGLE_DEFAULT_BOOK,
                        false => &book.id,
                    };
                    book.url = format!("{base_url}/{segment}");
                }
                Ok(books)
            }
        }
    }

    /// Lists every card of an account-level backend (JMAP, Google) in
    /// one pass, each carrying its addressbook memberships as book ids.
    pub fn list_account_cards(
        &mut self,
        base_url: &str,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, BridgeError> {
        match Backend::of(base_url) {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.list_jmap_cards(&session_url, credentials)
            }
            Backend::Google => self.list_google_cards(credentials.password),
            backend => Err(format!(
                "Cards of a {} account are listed per collection",
                backend.name(),
            )
            .into()),
        }
    }

    /// Lists the cards of the addressbook collection at
    /// `addressbook_url` (CardDAV, Graph); the account-level backends
    /// list once per account through [`Self::list_account_cards`].
    pub fn list_cards(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, BridgeError> {
        match Backend::of(base_url) {
            Backend::Carddav => self.list_carddav_cards(&parse_url(addressbook_url)?, credentials),
            Backend::Graph => self.list_graph_cards(
                credentials.password,
                account::graph_folder(base_url, addressbook_url),
            ),
            backend => Err(format!(
                "Cards of a {} account are listed account-wide",
                backend.name(),
            )
            .into()),
        }
    }

    /// Lists the collection's changes since the given cursor, on every
    /// backend: a CardDAV sync-collection REPORT (RFC 6578), a Graph
    /// contacts delta round, a JMAP ContactCard/changes round or a
    /// People connections sync. No cursor runs the initial round: the
    /// complete member set plus the cursor to delta from next time. A
    /// cursor the server no longer accepts re-runs an initial round,
    /// and an initial CardDAV sync a server rejects outright falls
    /// back to the plain enumeration; the returned delta says whether
    /// it was a complete round.
    pub fn sync_cards(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        cursor: Option<&str>,
    ) -> Result<CardDelta, BridgeError> {
        let round = self.sync_cards_round(base_url, addressbook_url, credentials, cursor);

        let delta = match round {
            Ok(delta) => delta,
            Err(_) if cursor.is_none() && Backend::of(base_url) == Backend::Carddav => {
                // NOTE: no sync-collection support: fall back to the
                // plain enumeration (a genuine outage fails there too).
                let url = parse_url(addressbook_url)?;
                let refs = self.enum_cards(&url, credentials)?;

                let changed = refs
                    .into_iter()
                    .map(|entry| Card {
                        id: entry.id,
                        uri: entry.uri,
                        etag: entry.etag,
                        vcard: String::new(),
                        books: Vec::new(),
                    })
                    .collect();
                return Ok(CardDelta {
                    changed,
                    vanished: Vec::new(),
                    token: None,
                    complete: true,
                });
            }
            Err(err) => return Err(err),
        };

        if let Some(mut delta) = delta {
            delta.complete = cursor.is_none();
            return Ok(delta);
        }

        // NOTE: the cursor was rejected, so re-run an initial round; a
        // still-rejected round must error, never read as an empty
        // collection (that would look remote-deleted).
        match self.sync_cards_round(base_url, addressbook_url, credentials, None)? {
            Some(mut delta) => {
                delta.complete = true;
                Ok(delta)
            }
            None => Err(format!("Initial sync round rejected for {addressbook_url}").into()),
        }
    }

    /// One sync round against the backend behind the base URL;
    /// [`None`] when the server rejected the cursor.
    fn sync_cards_round(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        cursor: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        match Backend::of(base_url) {
            Backend::Carddav => {
                let url = parse_url(addressbook_url)?;
                let delta = self.sync_carddav_cards(&url, credentials, cursor)?;
                Ok(delta.map(into_card_delta))
            }
            Backend::Graph => self.delta_graph_cards(
                credentials.password,
                account::graph_folder(base_url, addressbook_url),
                cursor,
            ),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.changes_jmap_cards(&session_url, credentials, cursor)
            }
            Backend::Google => self.sync_google_cards(credentials.password, cursor),
        }
    }

    /// Creates the card in the addressbook collection, returning it
    /// with its ETag. CardDAV names the resource `<id>.vcf` itself;
    /// the other backends name it server-side, so there the returned
    /// card carries the server-assigned id.
    pub fn create_card(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        id: &str,
        vcard: &str,
    ) -> Result<Card, BridgeError> {
        match Backend::of(base_url) {
            Backend::Carddav => {
                let url = parse_url(addressbook_url)?;
                let etag = self.create_carddav_card(&url, credentials, id, vcard)?;

                Ok(Card {
                    id: id.to_string(),
                    uri: format!("{id}.vcf"),
                    etag,
                    vcard: vcard.to_string(),
                    books: Vec::new(),
                })
            }
            Backend::Graph => self.create_graph_card(
                credentials.password,
                account::graph_folder(base_url, addressbook_url),
                vcard,
            ),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                let book_id = account::book_segment(base_url, addressbook_url);
                self.create_jmap_card(&session_url, credentials, book_id, vcard)
            }
            Backend::Google => self.create_google_card(credentials.password, vcard),
        }
    }

    /// Reads the card at the given resource name (as the server
    /// returned it) from the addressbook collection.
    pub fn read_card(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        uri: &str,
    ) -> Result<Card, BridgeError> {
        match Backend::of(base_url) {
            Backend::Carddav => {
                self.read_carddav_card(&parse_url(addressbook_url)?, credentials, uri)
            }
            Backend::Graph => self.read_graph_card(credentials.password, uri),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.read_jmap_card(&session_url, credentials, uri)
            }
            Backend::Google => self.read_google_card(credentials.password, uri),
        }
    }

    /// Updates the card in the addressbook collection, returning it
    /// with its new ETag. The base vCard (the state last synced with
    /// the server, [`None`] when unknown) trims the Graph, JMAP and
    /// Google patches to the fields the edit changed; CardDAV PUTs the
    /// full vCard and ignores it. The ETag guards the CardDAV and
    /// Google updates; Graph and JMAP carry no guard (last-write-wins).
    #[allow(clippy::too_many_arguments)]
    pub fn update_card(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        id: &str,
        uri: &str,
        vcard: &str,
        base_vcard: Option<&str>,
        etag: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let backend = Backend::of(base_url);
        let key = account::addressing_key(backend, id, uri);

        match backend {
            Backend::Carddav => {
                let url = parse_url(addressbook_url)?;
                let etag = self.update_carddav_card(&url, credentials, &key, vcard, etag)?;

                Ok(Card {
                    id: id.to_string(),
                    uri: key,
                    etag,
                    vcard: vcard.to_string(),
                    books: Vec::new(),
                })
            }
            Backend::Graph => self.update_graph_card(credentials.password, &key, vcard, base_vcard),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.update_jmap_card(&session_url, credentials, &key, vcard, base_vcard)
            }
            Backend::Google => {
                self.update_google_card(credentials.password, &key, vcard, base_vcard, etag)
            }
        }
    }

    /// Deletes the card from the addressbook collection; the ETag
    /// guards the CardDAV deletion only.
    pub fn delete_card(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        id: &str,
        uri: &str,
        etag: Option<&str>,
    ) -> Result<(), BridgeError> {
        let backend = Backend::of(base_url);
        let key = account::addressing_key(backend, id, uri);

        match backend {
            Backend::Carddav => {
                self.delete_carddav_card(&parse_url(addressbook_url)?, credentials, &key, etag)
            }
            Backend::Graph => self.delete_graph_card(credentials.password, &key),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.delete_jmap_card(&session_url, credentials, &key)
            }
            Backend::Google => self.delete_google_card(credentials.password, &key),
        }
    }

    /// Creates a batch of cards in the addressbook collection,
    /// returning the created cards in input order. Google-only: People
    /// is the one backend with a batch create verb, and its per-minute
    /// write quota throttles per-card calls on large rounds.
    pub fn create_cards(
        &mut self,
        base_url: &str,
        credentials: &Credentials,
        vcards: &[String],
    ) -> Result<Vec<Card>, BridgeError> {
        match Backend::of(base_url) {
            Backend::Google => self.create_google_cards(credentials.password, vcards),
            _ => Err("Batch card creation is Google-only".into()),
        }
    }

    /// Deletes a batch of cards from the addressbook collection by id.
    /// Google-only, like [`Self::create_cards`].
    pub fn delete_cards(
        &mut self,
        base_url: &str,
        credentials: &Credentials,
        ids: &[String],
    ) -> Result<(), BridgeError> {
        match Backend::of(base_url) {
            Backend::Google => self.delete_google_cards(credentials.password, ids),
            _ => Err("Batch card deletion is Google-only".into()),
        }
    }

    /// Pushes one round of changes as batch calls: JMAP folds the
    /// whole round (creates, content updates, membership patches,
    /// destroys) into ContactCard/set requests, Graph into $batch
    /// requests, both answering one outcome per change so a rejected
    /// card no longer fails its round. The other backends have their
    /// own shapes (Google batch verbs, CardDAV per-card requests).
    pub fn push_cards(
        &mut self,
        base_url: &str,
        addressbook_url: &str,
        credentials: &Credentials,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        match Backend::of(base_url) {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                let book_id = account::book_segment(base_url, addressbook_url);
                self.push_jmap_cards(&session_url, credentials, book_id, changes)
            }
            Backend::Graph => self.push_graph_cards(
                credentials.password,
                account::graph_folder(base_url, addressbook_url),
                changes,
            ),
            _ => Err("Batch card push is JMAP and Graph only".into()),
        }
    }

    /// Adds and removes the card's addressbook memberships on an
    /// account-level backend, by book id: JMAP patches addressBookIds,
    /// Google modifies group members.
    pub fn update_card_books(
        &mut self,
        base_url: &str,
        credentials: &Credentials,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        match Backend::of(base_url) {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.update_jmap_card_books(&session_url, credentials, id, add, remove)
            }
            Backend::Google => self.update_google_card_books(credentials.password, id, add, remove),
            backend => Err(format!(
                "Memberships of a {} card are fixed to its collection",
                backend.name(),
            )
            .into()),
        }
    }
}

/// Parses an absolute URL off the JNI wire.
fn parse_url(raw: &str) -> Result<Url, BridgeError> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}").into())
}

/// A sync-collection delta as the unified card delta shape, each
/// member href mapped to its resource name (the collection's own href,
/// when a server lists it, yields an empty name and is skipped).
fn into_card_delta(delta: SyncDelta) -> CardDelta {
    let changed = delta
        .changed
        .into_iter()
        .filter_map(|change| {
            let uri = href_resource(&change.href)?;
            Some(Card {
                id: uri.trim_end_matches(".vcf").to_string(),
                uri,
                etag: change.etag,
                vcard: String::new(),
                books: Vec::new(),
            })
        })
        .collect();
    let vanished = delta
        .vanished
        .iter()
        .filter_map(|href| href_resource(href))
        .collect();

    CardDelta {
        changed,
        vanished,
        token: delta.sync_token,
        complete: false,
    }
}

/// The last non-empty path segment of a member href, i.e. its resource
/// name; [`None`] for the collection itself (trailing slash).
fn href_resource(href: &str) -> Option<String> {
    let name = href.trim_end_matches('/');
    let name = &name[name.rfind('/').map_or(0, |slash| slash + 1)..];

    if name.is_empty() || href.ends_with('/') {
        return None;
    }
    Some(name.to_string())
}
