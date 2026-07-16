//! JMAP operations: the RFC 9610 AddressBook and ContactCard verbs, the
//! ContactCard/set push and the ContactCard/changes sync round.

use core::error::Error as StdError;

use std::collections::{BTreeMap, BTreeSet};

use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_jmap::{
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{
        changes::*, coroutine::JmapRedirectYield, error::JmapMethodError, session::JmapSession,
        session_get::*,
    },
    rfc9610::{
        address_book::{JmapAddressBook, get::*},
        contact_card::{JmapContactCard, changes::*, get::*, query::*, set::*},
    },
};
use secrecy::SecretString;
use serde_json::Value;
use url::Url;

use crate::{
    client::{
        Client,
        convert::{coroutine_error, rejected, required},
    },
    jmap,
    types::{Addressbook, BridgeError, Card, CardDelta, Credentials, PushChange, PushOutcome},
};

/// How many changes one JMAP ContactCard/set call carries: well under
/// every server's advertised maxObjectsInSet, so no session lookup.
const JMAP_SET_CHUNK: usize = 50;

/// JMAP operations: the RFC 9610 AddressBook and ContactCard verbs, the
/// ContactCard/set push and the ContactCard/changes sync round.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the account's JMAP AddressBooks (RFC 9610 §2.1).
    /// Collection URLs are left empty: the caller composes them, since
    /// only it knows the account they belong to. Doubles as the
    /// connection check during onboarding.
    pub fn list_jmap_addressbooks(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let coroutine =
            JmapAddressBookGet::new(&session, &auth, JmapAddressBookGetOptions::default())
                .map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        Ok(out
            .address_books
            .into_iter()
            .map(jmap_addressbook)
            .collect())
    }

    /// Lists the account's ContactCards across every AddressBook, each
    /// converted to a vCard document; the ContactCard id is the
    /// addressing key, a JSON hash the ETag, and the addressBookIds
    /// the card's books (natively m:n).
    pub fn list_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardQueryOptions::default();
        let coroutine =
            JmapContactCardQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let cards: Result<Vec<Card>, String> = out.cards.into_iter().map(jmap::to_card).collect();
        Ok(cards?)
    }

    /// Creates the vCard as a ContactCard in the AddressBook
    /// `book_id`. The server names the card, so the returned card
    /// carries the server-assigned id.
    pub fn create_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        book_id: &str,
        vcard: &str,
    ) -> Result<Card, BridgeError> {
        let card = jmap::to_jscontact(vcard)?;
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let create = BTreeMap::from([(
            "c0".to_string(),
            JmapContactCard {
                id: None,
                address_book_ids: BTreeMap::from([(book_id.to_string(), true)]),
                card,
            },
        )]);
        let args = JmapContactCardSetArgs {
            create: Some(create),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_created.into_values().next() {
            return Err(format!("JMAP ContactCard create rejected: {err:?}").into());
        }
        let id = out
            .created
            .into_values()
            .next()
            .and_then(|created| created.id)
            .ok_or_else(|| "JMAP create response is missing the card id".to_string())?;

        Ok(Card {
            id: id.clone(),
            uri: id,
            etag: None,
            vcard: vcard.to_string(),
            books: vec![book_id.to_string()],
        })
    }

    /// Reads the ContactCard `id`, converted to a vCard document.
    pub fn read_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<Card, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardGetOptions {
            ids: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let card = out
            .cards
            .into_iter()
            .next()
            .ok_or_else(|| format!("JMAP ContactCard `{id}` not found"))?;

        Ok(jmap::to_card(card)?)
    }

    /// Lists the ContactCard changes since `since_state` (RFC 8620
    /// `/changes`), the changed cards fetched in full so their
    /// JSON-hash ETag doubles as the content revision; without a
    /// state, the initial round gets every card plus the state to
    /// delta from next time. Returns [`None`] when the server can no
    /// longer compute changes from the state, so the caller falls
    /// back to an initial round.
    pub fn changes_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        since_state: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let Some(since) = since_state else {
            let opts = JmapContactCardGetOptions::default();
            let coroutine =
                JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;

            let changed: Vec<Card> = out
                .cards
                .into_iter()
                .map(jmap::to_card)
                .collect::<Result<_, _>>()?;
            return Ok(Some(CardDelta {
                changed,
                vanished: Vec::new(),
                token: Some(out.new_state),
                complete: false,
            }));
        };

        let mut cursor = since.to_string();
        let mut changed_ids = BTreeSet::new();
        let mut vanished = BTreeSet::new();

        loop {
            let opts = JmapContactCardChangesOptions::default();
            let coroutine = JmapContactCardChanges::new(&session, &auth, cursor.clone(), opts)
                .map_err(|err| err.to_string())?;
            let out = match self.run_jmap_changes(&api_url, coroutine)? {
                Some(out) => out,
                None => return Ok(None),
            };

            changed_ids.extend(out.created);
            changed_ids.extend(out.updated);
            vanished.extend(out.destroyed);
            cursor = out.new_state;

            if !out.has_more_changes {
                break;
            }
        }

        // NOTE: a card created and destroyed within the window is in
        // both lists; the destroy wins.
        changed_ids.retain(|id| !vanished.contains(id));

        let mut changed = Vec::new();
        if !changed_ids.is_empty() {
            let opts = JmapContactCardGetOptions {
                ids: Some(changed_ids.into_iter().collect()),
                ..Default::default()
            };
            let coroutine =
                JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;
            changed = out
                .cards
                .into_iter()
                .map(jmap::to_card)
                .collect::<Result<_, _>>()?;
        }

        Ok(Some(CardDelta {
            changed,
            vanished: vanished.into_iter().collect(),
            token: Some(cursor),
            complete: false,
        }))
    }

    /// Updates the ContactCard `id` from the vCard. With a base vCard
    /// (the state last synced with the server) the patch shrinks to
    /// the properties the edit changed, plus nulls for the removed
    /// ones; without one it replaces every property the vCard carries.
    /// There is no If-Match guard, updates are last-write-wins.
    pub fn update_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let patch = jmap::to_patch(vcard, base_vcard)?;
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let update = BTreeMap::from([(id.to_string(), JmapContactCardPatch(patch))]);
        let args = JmapContactCardSetArgs {
            update: Some(update),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_updated.into_values().next() {
            return Err(format!("JMAP ContactCard update rejected: {err:?}").into());
        }

        Ok(Card {
            id: id.to_string(),
            uri: id.to_string(),
            etag: None,
            vcard: vcard.to_string(),
            books: Vec::new(),
        })
    }

    /// Destroys the ContactCard `id`.
    pub fn delete_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let args = JmapContactCardSetArgs {
            destroy: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_destroyed.into_values().next() {
            return Err(format!("JMAP ContactCard destroy rejected: {err:?}").into());
        }

        Ok(())
    }

    /// Adds and removes AddressBook memberships of the ContactCard
    /// `id` in one patch (RFC 9610 addressBookIds is m:n: an added
    /// book id patches to true, a removed one to null).
    pub fn update_jmap_card_books(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mut patch = BTreeMap::new();
        for book in add {
            patch.insert(format!("addressBookIds/{book}"), Value::Bool(true));
        }
        for book in remove {
            patch.insert(format!("addressBookIds/{book}"), Value::Null);
        }

        let update = BTreeMap::from([(id.to_string(), JmapContactCardPatch(patch))]);
        let args = JmapContactCardSetArgs {
            update: Some(update),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_updated.into_values().next() {
            return Err(format!("JMAP membership update rejected: {err:?}").into());
        }

        Ok(())
    }

    /// Pushes one round of changes as ContactCard/set calls (chunks of
    /// [`JMAP_SET_CHUNK`]): creates, content updates, membership
    /// patches and destroys ride the same request, and the per-object
    /// response maps back to one outcome per change (a destroy the
    /// server no longer finds reads as already converged, and a
    /// rejected object no longer fails its round).
    pub(crate) fn push_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        book_id: &str,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mut outcomes = Vec::with_capacity(changes.len());
        for chunk in changes.chunks(JMAP_SET_CHUNK) {
            let mut create = BTreeMap::new();
            let mut create_refs = BTreeMap::new();
            let mut update = BTreeMap::new();
            let mut update_refs = BTreeMap::new();
            let mut destroy = Vec::new();
            let mut destroy_refs = BTreeMap::new();

            for (index, change) in chunk.iter().enumerate() {
                match change.op.as_str() {
                    "create" => {
                        let vcard = required(&change.vcard, "create", "vcard")?;
                        let key = format!("c{index}");
                        create.insert(
                            key.clone(),
                            JmapContactCard {
                                id: None,
                                address_book_ids: BTreeMap::from([(book_id.to_string(), true)]),
                                card: jmap::to_jscontact(vcard)?,
                            },
                        );
                        create_refs.insert(key, change.reference.clone());
                    }
                    "update" => {
                        let id = required(&change.id, "update", "id")?;
                        let vcard = required(&change.vcard, "update", "vcard")?;
                        let patch = jmap::to_patch(vcard, change.base_vcard.as_deref())?;
                        update.insert(id.to_string(), JmapContactCardPatch(patch));
                        update_refs.insert(id.to_string(), change.reference.clone());
                    }
                    "books" => {
                        let id = required(&change.id, "books", "id")?;
                        let mut patch = BTreeMap::new();
                        for book in &change.add {
                            patch.insert(format!("addressBookIds/{book}"), Value::Bool(true));
                        }
                        for book in &change.remove {
                            patch.insert(format!("addressBookIds/{book}"), Value::Null);
                        }
                        update.insert(id.to_string(), JmapContactCardPatch(patch));
                        update_refs.insert(id.to_string(), change.reference.clone());
                    }
                    "destroy" => {
                        let id = required(&change.id, "destroy", "id")?;
                        destroy.push(id.to_string());
                        destroy_refs.insert(id.to_string(), change.reference.clone());
                    }
                    op => return Err(format!("Unknown push op `{op}`").into()),
                }
            }

            let args = JmapContactCardSetArgs {
                create: (!create.is_empty()).then_some(create),
                update: (!update.is_empty()).then_some(update),
                destroy: (!destroy.is_empty()).then_some(destroy),
            };
            let coroutine =
                JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;

            for (key, reference) in create_refs {
                if let Some(err) = out.not_created.get(&key) {
                    outcomes.push(rejected(reference, format!("{err:?}")));
                } else if let Some(id) = out.created.get(&key).and_then(|card| card.id.clone()) {
                    outcomes.push(PushOutcome {
                        reference,
                        accepted: true,
                        id: Some(id),
                        ..Default::default()
                    });
                } else {
                    return Err("JMAP create response is missing the card id".into());
                }
            }
            for (id, reference) in update_refs {
                match out.not_updated.get(&id) {
                    Some(err) => outcomes.push(rejected(reference, format!("{err:?}"))),
                    None => outcomes.push(PushOutcome {
                        reference,
                        accepted: true,
                        ..Default::default()
                    }),
                }
            }
            for (id, reference) in destroy_refs {
                match out.not_destroyed.get(&id) {
                    // NOTE: NotFound means already gone upstream, so the
                    // destroy converged.
                    Some(JmapContactCardSetItemError::NotFound { .. }) | None => {
                        outcomes.push(PushOutcome {
                            reference,
                            accepted: true,
                            ..Default::default()
                        })
                    }
                    Some(err) => outcomes.push(rejected(reference, format!("{err:?}"))),
                }
            }
        }

        Ok(outcomes)
    }
}

/// JMAP coroutine runners: the JMAP session fetch and the resume loops
/// that pump a JMAP method coroutine, routing every yield to the
/// transport stream opened on the session's API URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Fetches the JMAP session (RFC 8620 §2) from the session URL
    /// (a bare origin triggers /.well-known/jmap discovery), rebuilding
    /// the coroutine whenever the server answers 3xx.
    fn jmap_session(
        &mut self,
        session_url: &Url,
        http_auth: &SecretString,
    ) -> Result<JmapSession, BridgeError> {
        let mut target = session_url.clone();

        loop {
            let mut coroutine = JmapSessionGet::new(http_auth, &target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    JmapCoroutineState::Complete(Ok(out)) => return Ok(out.session),
                    JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsWrite(bytes)) => {
                        self.write(target.as_str(), &bytes)?;
                        arg = None;
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRead) => {
                        arg = Some(self.read(target.as_str())?);
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRedirect {
                        url, ..
                    }) => {
                        target = url;
                        break;
                    }
                }
            }
        }
    }

    /// Runs a JMAP method coroutine to completion, routing every yield
    /// to the transport stream opened on the session's API URL.
    fn run_jmap<C, T, E>(&mut self, api_url: &Url, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(value)) => return Ok(value),
                JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Drives one JMAP `ContactCard/changes` round, surfacing an
    /// uncomputable state as [`None`] instead of an error (the generic
    /// [`Self::run_jmap`] erases the error variant the fallback needs).
    fn run_jmap_changes(
        &mut self,
        api_url: &Url,
        mut coroutine: JmapContactCardChanges,
    ) -> Result<Option<JmapChangesOutput>, BridgeError> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(out)) => return Ok(Some(out)),
                JmapCoroutineState::Complete(Err(JmapContactCardChangesError::Changes(
                    JmapChangesError::Method(JmapMethodError::CannotCalculateChanges { .. }),
                ))) => return Ok(None),
                JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// Authorization header value from the credentials: an empty login means
/// the password field carries an OAuth 2.0 access token (Bearer),
/// otherwise HTTP Basic.
fn jmap_auth(credentials: &Credentials) -> SecretString {
    let value = if credentials.login.is_empty() {
        HttpAuthBearer::new(credentials.password).to_authorization()
    } else {
        HttpAuthBasic::new(credentials.login, credentials.password).to_authorization()
    };

    SecretString::from(value)
}

/// io-jmap AddressBook to the JNI-facing shape: the display name
/// defaults to the id when the server returned none, and the absolute
/// collection URL is left empty, composed by the caller.
fn jmap_addressbook(book: JmapAddressBook) -> Addressbook {
    let id = book.id.unwrap_or_default();

    Addressbook {
        name: book.name.unwrap_or_else(|| id.clone()),
        id,
        url: String::new(),
        description: book.description,
        color: None,
    }
}
