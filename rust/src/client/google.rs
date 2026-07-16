//! Google People operations: contact groups and connections as
//! addressbooks and cards, the batch create and delete verbs, group
//! membership edits and the sync-token round.

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_people::{
    coroutine::{PeopleCoroutine, PeopleCoroutineState, PeopleYield},
    v1::{
        rest::{
            contact_groups::{
                PeopleContactGroupType,
                list::{PeopleContactGroupsList, PeopleContactGroupsListParams},
                members::modify::PeopleContactGroupMembersModify,
            },
            people::{
                PeoplePerson, PeoplePersonField,
                batch_create_contacts::PeopleContactsBatchCreate,
                batch_delete_contacts::PeopleContactsBatchDelete,
                connections::list::{PeopleConnectionsList, PeopleConnectionsListParams},
                create_contact::PeopleContactCreate,
                delete_contact::PeopleContactDelete,
                get::PeoplePersonGet,
                update_contact::PeopleContactUpdate,
            },
        },
        send::{PEOPLE_API_BASE, PeopleSendError, PeopleSendOutput},
    },
};

use crate::{
    client::{Client, convert::coroutine_error},
    google,
    types::{Addressbook, BridgeError, Card, CardDelta},
};

/// How many contacts one people.batchCreateContacts call carries.
const GOOGLE_CREATE_CHUNK: usize = 200;

/// How many resource names one people.batchDeleteContacts call carries.
const GOOGLE_DELETE_CHUNK: usize = 500;

/// Google People operations: contact groups and connections as
/// addressbooks and cards, the batch create and delete verbs, group
/// membership edits and the sync-token round.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the Google account's contact groups as addressbooks: the
    /// myContacts system group first (as Contacts, the group every
    /// contact belongs to), then the user's own groups. Memberships
    /// are m:n labels, so one card can appear under several books.
    /// Doubles as the connection check during onboarding. Collection
    /// URLs are left empty: the caller composes them.
    pub fn list_google_addressbooks(
        &mut self,
        token: &str,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let mut books = Vec::new();
        let mut page_token: Option<String> = None;
        loop {
            let params = PeopleContactGroupsListParams {
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine =
                PeopleContactGroupsList::new(&auth, &[], &params).map_err(|err| err.to_string())?;
            let page = self.run_google(coroutine)?;

            for group in page.contact_groups {
                if group.metadata.as_ref().and_then(|m| m.deleted) == Some(true) {
                    continue;
                }

                let id = group
                    .resource_name
                    .strip_prefix("contactGroups/")
                    .unwrap_or(&group.resource_name)
                    .to_string();
                if id.is_empty() {
                    continue;
                }

                // NOTE: of the system groups, only myContacts is a
                // container; the others are not addressbooks.
                if id == "myContacts" {
                    books.insert(
                        0,
                        Addressbook {
                            id,
                            name: "Contacts".to_string(),
                            url: String::new(),
                            description: None,
                            color: None,
                        },
                    );
                } else if group.group_type == Some(PeopleContactGroupType::UserContactGroup) {
                    let name = group
                        .name
                        .or(group.formatted_name)
                        .unwrap_or_else(|| id.clone());
                    books.push(Addressbook {
                        id,
                        name,
                        url: String::new(),
                        description: None,
                        color: None,
                    });
                }
            }

            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => break,
            }
        }

        Ok(books)
    }

    /// Lists the account's contacts (`people.connections.list`), each
    /// projected onto a vCard document; the person id is the addressing
    /// key and the person etag the ETag.
    pub fn list_google_cards(&mut self, token: &str) -> Result<Vec<Card>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let params = PeopleConnectionsListParams {
            page_size: Some(100),
            ..Default::default()
        };
        let coroutine = PeopleConnectionsList::new(&auth, google::READ_FIELDS, &params)
            .map_err(|err| err.to_string())?;
        let mut page = self.run_google(coroutine)?;

        let mut cards = Vec::new();
        loop {
            cards.extend(page.connections.into_iter().map(google_card));

            match page.next_page_token {
                Some(next) => {
                    let params = PeopleConnectionsListParams {
                        page_size: Some(100),
                        page_token: Some(&next),
                        ..Default::default()
                    };
                    let coroutine = PeopleConnectionsList::new(&auth, google::READ_FIELDS, &params)
                        .map_err(|err| err.to_string())?;
                    page = self.run_google(coroutine)?;
                }
                None => break,
            }
        }

        Ok(cards)
    }

    /// Lists the People contact changes since `sync_token` (deleted
    /// persons ride flagged in the response); without a token, the
    /// initial round lists every contact and requests the token to
    /// delta from next time. Returns [`None`] when the server expired
    /// the token, so the caller falls back to an initial round.
    pub fn sync_google_cards(
        &mut self,
        token: &str,
        sync_token: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        // NOTE: metadata carries the deleted marker; a sync token binds
        // to its field mask, so every round asks the same one.
        let fields: Vec<PeoplePersonField> = google::READ_FIELDS
            .iter()
            .copied()
            .chain([PeoplePersonField::Metadata])
            .collect();

        let mut delta = CardDelta {
            changed: Vec::new(),
            vanished: Vec::new(),
            token: None,
            complete: false,
        };
        let mut page_token: Option<String> = None;

        loop {
            let params = PeopleConnectionsListParams {
                page_size: Some(100),
                page_token: page_token.as_deref(),
                request_sync_token: true,
                sync_token,
                ..Default::default()
            };
            let coroutine = PeopleConnectionsList::new(&auth, &fields, &params)
                .map_err(|err| err.to_string())?;
            let page = match self.run_google(coroutine) {
                Ok(page) => page,
                Err(err) if err.message.contains("EXPIRED_SYNC_TOKEN") => return Ok(None),
                Err(err) => return Err(err),
            };

            for person in page.connections {
                let deleted = person
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.deleted)
                    .unwrap_or(false);
                if deleted {
                    delta
                        .vanished
                        .push(google::person_id(&person.resource_name).to_string());
                } else {
                    delta.changed.push(google_card(person));
                }
            }

            if page.next_sync_token.is_some() {
                delta.token = page.next_sync_token;
            }
            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => break,
            }
        }

        Ok(Some(delta))
    }

    /// Creates the vCard as a People contact. The server names the
    /// resource, so the returned card carries the server-assigned id.
    pub fn create_google_card(&mut self, token: &str, vcard: &str) -> Result<Card, BridgeError> {
        let person = google::to_person(vcard)?;
        let auth = HttpAuthBearer::new(token);

        let coroutine = PeopleContactCreate::new(&auth, &person, google::READ_FIELDS, &[])
            .map_err(|err| err.to_string())?;
        Ok(google_card(self.run_google(coroutine)?))
    }

    /// Reads the People contact `id`, projected onto a vCard document.
    pub fn read_google_card(&mut self, token: &str, id: &str) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            PeoplePersonGet::new(&auth, &format!("people/{id}"), google::READ_FIELDS, &[])
                .map_err(|err| err.to_string())?;

        Ok(google_card(self.run_google(coroutine)?))
    }

    /// Updates the People contact `id` from the vCard. With a base
    /// vCard (the state last synced with the server) the update mask
    /// shrinks to the fields the edit changed; without one every
    /// managed field is replaced. People requires the person's current
    /// etag on updates, so a missing one is fetched first; a stale one
    /// fails the update (no silent last-write-wins). A stash write
    /// (clientData in the mask) merges the server's foreign clientData
    /// entries under the same guard.
    pub fn update_google_card(
        &mut self,
        token: &str,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
        etag: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let resource_name = format!("people/{id}");

        let mut person = google::to_person(vcard)?;
        person.resource_name = resource_name.clone();

        let fields = match base_vcard {
            Some(base) => google::changed_fields(&person, &google::to_person(base)?),
            None => google::MANAGED_FIELDS.to_vec(),
        };
        if fields.is_empty() {
            return Ok(Card {
                id: id.to_string(),
                uri: id.to_string(),
                etag: etag.map(str::to_string),
                vcard: vcard.to_string(),
                books: Vec::new(),
            });
        }

        // NOTE: a masked clientData update replaces the whole list, so
        // the server's foreign entries are merged in first (the etag
        // guards the race); the same fetch sources a missing etag.
        let needs_merge = fields.contains(&PeoplePersonField::ClientData);
        if needs_merge || etag.is_none() {
            let coroutine =
                PeoplePersonGet::new(&auth, &resource_name, &[PeoplePersonField::ClientData], &[])
                    .map_err(|err| err.to_string())?;
            let current = self.run_google(coroutine)?;

            if needs_merge {
                let mut merged: Vec<_> = current
                    .client_data
                    .into_iter()
                    .filter(|entry| entry.key.as_deref() != Some(google::CLIENT_DATA_KEY))
                    .collect();
                merged.append(&mut person.client_data);
                person.client_data = merged;
            }
            person.etag = match etag {
                Some(etag) => etag.to_string(),
                None => current.etag,
            };
        } else {
            person.etag = etag.unwrap_or_default().to_string();
        }

        let coroutine = PeopleContactUpdate::new(&auth, &person, &fields, google::READ_FIELDS, &[])
            .map_err(|err| err.to_string())?;
        match self.run_google(coroutine) {
            Ok(updated) => Ok(google_card(updated)),
            // NOTE: the connections.list etag the engine carries is
            // rejected by updateContact (only a people.get etag is
            // accepted), so re-read the etag and retry once; the engine
            // still guards concurrency by re-conflicting the row.
            Err(err)
                if err.status == Some(400) && err.message.to_ascii_lowercase().contains("etag") =>
            {
                let coroutine = PeoplePersonGet::new(
                    &auth,
                    &resource_name,
                    &[PeoplePersonField::ClientData],
                    &[],
                )
                .map_err(|err| err.to_string())?;
                person.etag = self.run_google(coroutine)?.etag;

                let coroutine =
                    PeopleContactUpdate::new(&auth, &person, &fields, google::READ_FIELDS, &[])
                        .map_err(|err| err.to_string())?;
                Ok(google_card(self.run_google(coroutine)?))
            }
            Err(err) => Err(err),
        }
    }

    /// Deletes the People contact `id`.
    pub fn delete_google_card(&mut self, token: &str, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = PeopleContactDelete::new(&auth, &format!("people/{id}"))
            .map_err(|err| err.to_string())?;
        self.run_google(coroutine)?;

        Ok(())
    }

    /// Creates the vCards as People contacts by batch calls (200 per
    /// request instead of one), returning the created cards in input
    /// order: a create carries no correlation key, so request order is
    /// the response's contract. The server names the resources, so the
    /// returned cards carry the server-assigned ids.
    pub fn create_google_cards(
        &mut self,
        token: &str,
        vcards: &[String],
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let persons = vcards
            .iter()
            .map(|vcard| google::to_person(vcard))
            .collect::<Result<Vec<_>, String>>()?;

        let mut cards = Vec::with_capacity(persons.len());
        for chunk in persons.chunks(GOOGLE_CREATE_CHUNK) {
            let coroutine = PeopleContactsBatchCreate::new(&auth, chunk, google::READ_FIELDS, &[])
                .map_err(|err| err.to_string())?;
            let response = self.run_google(coroutine)?;

            // NOTE: a count mismatch would misattribute server ids to
            // vCards, so abort rather than guess.
            if response.created_people.len() != chunk.len() {
                return Err(format!(
                    "Google batch create answered {} contacts for {}",
                    response.created_people.len(),
                    chunk.len()
                )
                .into());
            }
            for created in response.created_people {
                let person = created
                    .person
                    .ok_or("Google batch create answered a personless entry")?;
                cards.push(google_card(person));
            }
        }

        Ok(cards)
    }

    /// Deletes the People contacts `ids` by batch calls (500 per
    /// request instead of one).
    pub fn delete_google_cards(&mut self, token: &str, ids: &[String]) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let names: Vec<String> = ids.iter().map(|id| format!("people/{id}")).collect();
        for chunk in names.chunks(GOOGLE_DELETE_CHUNK) {
            let coroutine =
                PeopleContactsBatchDelete::new(&auth, chunk).map_err(|err| err.to_string())?;
            self.run_google(coroutine)?;
        }

        Ok(())
    }

    /// Adds and removes the People contact `id` from contact groups,
    /// one members:modify call per group (the API only accepts adds
    /// into user groups; removing a contact from its last group is
    /// rejected server-side).
    pub fn update_google_card_books(
        &mut self,
        token: &str,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let person = vec![format!("people/{id}")];

        for group in add {
            let coroutine = PeopleContactGroupMembersModify::new(
                &auth,
                &format!("contactGroups/{group}"),
                &person,
                &[],
            )
            .map_err(|err| err.to_string())?;
            let out = self.run_google(coroutine)?;

            if !out.not_found_resource_names.is_empty() {
                return Err(format!(
                    "Google group member add rejected: {:?} not found",
                    out.not_found_resource_names
                )
                .into());
            }
        }

        for group in remove {
            let coroutine = PeopleContactGroupMembersModify::new(
                &auth,
                &format!("contactGroups/{group}"),
                &[],
                &person,
            )
            .map_err(|err| err.to_string())?;
            let out = self.run_google(coroutine)?;

            if !out
                .can_not_remove_last_contact_group_resource_names
                .is_empty()
            {
                return Err("Google refused to remove the contact from its last group".into());
            }
            if !out.not_found_resource_names.is_empty() {
                return Err(format!(
                    "Google group member removal rejected: {:?} not found",
                    out.not_found_resource_names
                )
                .into());
            }
        }

        Ok(())
    }
}

/// Google coroutine runner: the resume loop that pumps a Google People
/// coroutine, routing every yield to the transport stream opened on the
/// People API origin.
impl<'a, 'local> Client<'a, 'local> {
    /// Runs a Google People coroutine to completion, routing every
    /// yield to the transport stream opened on the People API origin.
    fn run_google<C, T>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: PeopleCoroutine<
                Yield = PeopleYield,
                Return = Result<PeopleSendOutput<T>, PeopleSendError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                PeopleCoroutineState::Complete(Ok(output)) => return Ok(output.response),
                PeopleCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                PeopleCoroutineState::Yielded(PeopleYield::WantsRead) => {
                    arg = Some(self.read(PEOPLE_API_BASE)?);
                }
                PeopleCoroutineState::Yielded(PeopleYield::WantsWrite(bytes)) => {
                    self.write(PEOPLE_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// io-people person to the JNI-facing card shape: the projected
/// vCard document, the person id (resource name minus the `people/`
/// prefix) as both display id and addressing key (uri), the person
/// etag as ETag, and the contact group memberships (minus their
/// `contactGroups/` prefix) as the card's books.
fn google_card(person: PeoplePerson) -> Card {
    let vcard = google::to_vcard(&person);
    let id = google::person_id(&person.resource_name).to_string();
    let books = person
        .memberships
        .iter()
        .filter_map(|membership| membership.contact_group_membership.as_ref())
        .filter_map(|group| {
            group
                .contact_group_resource_name
                .as_deref()
                .map(|name| name.strip_prefix("contactGroups/").unwrap_or(name))
                .or(group.contact_group_id.as_deref())
        })
        .map(str::to_string)
        .collect();

    Card {
        uri: id.clone(),
        id,
        etag: (!person.etag.is_empty()).then_some(person.etag),
        vcard,
        books,
    }
}
