//! Microsoft Graph operations: contact folders and contacts as
//! addressbooks and cards, plus the $batch push and the delta sync round.

use std::collections::BTreeMap;

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::users::{
            contact_folders::{MsgraphContactFoldersListResponse, list::MsgraphContactFoldersList},
            contacts::{
                MsgraphContact, MsgraphContactsDeltaResponse, MsgraphContactsListResponse,
                create::MsgraphContactCreate,
                delete::MsgraphContactDelete,
                delta::MsgraphContactsDelta,
                get::MsgraphContactGet,
                list::{MsgraphContactsList, MsgraphContactsListParams},
                update::MsgraphContactUpdate,
            },
        },
        send::{MSGRAPH_API_BASE, MsgraphSend, MsgraphSendError, MsgraphSendOutput},
    },
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use url::Url;

use crate::{
    client::{
        Client,
        convert::{coroutine_error, rejected, required},
    },
    msgraph,
    types::{Addressbook, BridgeError, Card, CardDelta, PushChange, PushOutcome},
};

/// How many inner requests one Graph $batch call carries (the
/// endpoint's hard limit).
const GRAPH_BATCH_CHUNK: usize = 20;

/// Microsoft Graph operations: contact folders and contacts as
/// addressbooks and cards, plus the $batch push and the delta sync round.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the Microsoft Graph contact folders as addressbooks,
    /// prepending the default Contacts folder (Graph serves it outside
    /// the folder list, addressed by an empty folder id). Collection
    /// URLs are left empty: the caller composes them, since only it
    /// knows the account they belong to.
    pub fn list_graph_addressbooks(
        &mut self,
        token: &str,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let mut books = vec![Addressbook {
            id: String::new(),
            name: "Contacts".to_string(),
            url: String::new(),
            description: None,
            color: None,
        }];

        let coroutine = MsgraphContactFoldersList::new(&auth, "me", &Default::default())
            .map_err(|err| err.to_string())?;
        let mut page = self.run_msgraph(coroutine)?;

        loop {
            for folder in page.value {
                books.push(Addressbook {
                    name: if folder.display_name.is_empty() {
                        folder.id.clone()
                    } else {
                        folder.display_name
                    },
                    id: folder.id,
                    url: String::new(),
                    description: None,
                    color: None,
                });
            }

            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = self.run_msgraph(
                        MsgraphSend::<MsgraphContactFoldersListResponse>::get(&auth, url),
                    )?;
                }
                None => break,
            }
        }

        Ok(books)
    }

    /// Lists the contacts of the Graph folder (empty id means the
    /// default Contacts folder), each projected onto a vCard document;
    /// the Graph id is the addressing key and the changeKey the ETag.
    pub fn list_graph_cards(
        &mut self,
        token: &str,
        folder: &str,
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let folder = (!folder.is_empty()).then_some(folder);

        let expand = graph_expand();
        let params = MsgraphContactsListParams {
            top: Some(100),
            expand: Some(&expand),
            ..Default::default()
        };
        let coroutine = MsgraphContactsList::new(&auth, "me", folder, &params)
            .map_err(|err| err.to_string())?;
        let mut page = self.run_msgraph(coroutine)?;

        let mut cards = Vec::new();
        loop {
            cards.extend(page.value.into_iter().map(graph_card));

            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = self
                        .run_msgraph(MsgraphSend::<MsgraphContactsListResponse>::get(&auth, url))?;
                }
                None => break,
            }
        }

        Ok(cards)
    }

    /// Creates the vCard as a Graph contact in the folder (empty id
    /// means the default Contacts folder). Graph names the resource,
    /// so the returned card carries the server-assigned id.
    pub fn create_graph_card(
        &mut self,
        token: &str,
        folder: &str,
        vcard: &str,
    ) -> Result<Card, BridgeError> {
        let contact = msgraph::to_new_contact(vcard)?;
        let auth = HttpAuthBearer::new(token);
        let folder = (!folder.is_empty()).then_some(folder);

        let coroutine = MsgraphContactCreate::new(&auth, "me", folder, &contact)
            .map_err(|err| err.to_string())?;
        let mut created = self.run_msgraph(coroutine)?;

        // NOTE: create responses cannot $expand, so the stash just sent
        // is re-attached before projecting the card.
        created.single_value_extended_properties =
            msgraph::to_contact(vcard)?.single_value_extended_properties;
        Ok(graph_card(created))
    }

    /// Reads the Graph contact `id`, projected onto a vCard document.
    pub fn read_graph_card(&mut self, token: &str, id: &str) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let expand = graph_expand();
        let coroutine = MsgraphContactGet::new(&auth, "me", id, Some(&expand))
            .map_err(|err| err.to_string())?;
        Ok(graph_card(self.run_msgraph(coroutine)?))
    }

    /// Updates the Graph contact `id` from the vCard. With a base
    /// vCard (the state last synced with the server) the PATCH body
    /// shrinks to the fields the edit changed; without one it falls
    /// back to the create-shaped body, which cannot clear fields but
    /// carries none of the nulls the Outlook backend rejects with HTTP
    /// 500. There is no If-Match guard, updates are last-write-wins.
    pub fn update_graph_card(
        &mut self,
        token: &str,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let contact = match base_vcard {
            Some(base) => msgraph::to_contact_delta(vcard, base)?,
            None => msgraph::to_new_contact(vcard)?,
        };
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphContactUpdate::new(&auth, "me", id, &contact).map_err(|err| err.to_string())?;
        let mut updated = self.run_msgraph(coroutine)?;

        // NOTE: update responses cannot $expand, so the full stash
        // (a delta may skip it in the body) is re-attached first.
        updated.single_value_extended_properties =
            msgraph::to_contact(vcard)?.single_value_extended_properties;
        Ok(graph_card(updated))
    }

    /// Deletes the Graph contact `id`.
    pub fn delete_graph_card(&mut self, token: &str, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphContactDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;

        Ok(())
    }

    /// Pushes one round of changes as $batch calls (chunks of
    /// [`GRAPH_BATCH_CHUNK`], the endpoint's hard limit): creates
    /// POST, updates PATCH (delta-trimmed like the per-card path),
    /// destroys DELETE, each inner status mapping back to one outcome
    /// per change (a 404 on a destroy reads as already converged, and
    /// a rejected change no longer fails its round).
    pub(crate) fn push_graph_cards(
        &mut self,
        token: &str,
        folder: &str,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let create_url = if folder.is_empty() {
            "/me/contacts".to_string()
        } else {
            format!("/me/contactFolders/{folder}/contacts")
        };

        let mut outcomes = Vec::with_capacity(changes.len());
        for chunk in changes.chunks(GRAPH_BATCH_CHUNK) {
            let mut requests = Vec::with_capacity(chunk.len());
            for (index, change) in chunk.iter().enumerate() {
                let request = match change.op.as_str() {
                    "create" => {
                        let vcard = required(&change.vcard, "create", "vcard")?;
                        GraphBatchRequest {
                            id: index.to_string(),
                            method: "POST",
                            url: create_url.clone(),
                            headers: Some(GraphBatchHeaders::JSON),
                            body: Some(msgraph::to_new_contact(vcard)?),
                        }
                    }
                    "update" => {
                        let id = required(&change.id, "update", "id")?;
                        let vcard = required(&change.vcard, "update", "vcard")?;
                        let contact = match change.base_vcard.as_deref() {
                            Some(base) => msgraph::to_contact_delta(vcard, base)?,
                            None => msgraph::to_new_contact(vcard)?,
                        };
                        GraphBatchRequest {
                            id: index.to_string(),
                            method: "PATCH",
                            url: format!("/me/contacts/{id}"),
                            headers: Some(GraphBatchHeaders::JSON),
                            body: Some(contact),
                        }
                    }
                    "destroy" => {
                        let id = required(&change.id, "destroy", "id")?;
                        GraphBatchRequest {
                            id: index.to_string(),
                            method: "DELETE",
                            url: format!("/me/contacts/{id}"),
                            headers: None,
                            body: None,
                        }
                    }
                    op => return Err(format!("Unknown Graph push op `{op}`").into()),
                };
                requests.push(request);
            }

            let url = parse_graph_url(&format!("{MSGRAPH_API_BASE}$batch"))?;
            let batch = GraphBatch { requests };
            let coroutine = MsgraphSend::<GraphBatchReplies>::post_json(&auth, url, &batch)
                .map_err(|err| err.to_string())?;
            let out = self.run_msgraph(coroutine)?;

            let mut replies: BTreeMap<String, GraphBatchReply> = out
                .responses
                .into_iter()
                .map(|reply| (reply.id.clone(), reply))
                .collect();

            for (index, change) in chunk.iter().enumerate() {
                let Some(reply) = replies.remove(&index.to_string()) else {
                    return Err(format!("Graph batch response is missing request {index}").into());
                };

                let accepted = (200..300).contains(&reply.status)
                    || (change.op == "destroy" && reply.status == 404);
                if accepted {
                    let body = reply.body.unwrap_or_default();
                    outcomes.push(PushOutcome {
                        reference: change.reference.clone(),
                        accepted: true,
                        id: body.get("id").and_then(|id| id.as_str()).map(Into::into),
                        etag: body
                            .get("changeKey")
                            .and_then(|key| key.as_str())
                            .map(Into::into),
                        ..Default::default()
                    });
                } else {
                    let message = reply
                        .body
                        .as_ref()
                        .and_then(|body| body.get("error"))
                        .and_then(|error| error.get("message"))
                        .and_then(|message| message.as_str())
                        .unwrap_or_default()
                        .to_string();
                    outcomes.push(rejected(
                        change.reference.clone(),
                        format!("HTTP {} {message}", reply.status),
                    ));
                }
            }
        }

        Ok(outcomes)
    }

    /// Lists the Graph contact changes since `delta_link` (removals
    /// ride as `@removed` rows); without a link, the initial round
    /// enumerates every contact and ends with the link to delta from
    /// next time. Rows carry only the id and changeKey: delta queries
    /// cannot `$expand` the stash extended property, so bodies are
    /// fetched separately. Returns [`None`] when the server expired
    /// the link (HTTP 410), so the caller falls back to an initial
    /// round.
    pub fn delta_graph_cards(
        &mut self,
        token: &str,
        folder: &str,
        delta_link: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let folder = (!folder.is_empty()).then_some(folder);

        let first = match delta_link {
            Some(link) => {
                let url = parse_graph_url(link)?;
                self.run_msgraph_delta(MsgraphSend::get(&auth, url))?
            }
            None => {
                let coroutine = MsgraphContactsDelta::new(&auth, "me", folder, Some("changeKey"))
                    .map_err(|err| err.to_string())?;
                self.run_msgraph_delta(coroutine)?
            }
        };
        let Some(mut page) = first else {
            return Ok(None);
        };

        let mut delta = CardDelta {
            changed: Vec::new(),
            vanished: Vec::new(),
            token: None,
            complete: false,
        };

        loop {
            for row in page.value {
                if row.removed.is_some() {
                    delta.vanished.push(row.contact.id);
                } else {
                    delta.changed.push(Card {
                        uri: row.contact.id.clone(),
                        id: row.contact.id,
                        etag: row.contact.change_key,
                        vcard: String::new(),
                        books: Vec::new(),
                    });
                }
            }

            if page.delta_link.is_some() {
                delta.token = page.delta_link;
            }
            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = match self.run_msgraph_delta(MsgraphSend::get(&auth, url))? {
                        Some(page) => page,
                        None => return Ok(None),
                    };
                }
                None => break,
            }
        }

        Ok(Some(delta))
    }
}

/// Graph coroutine runners: the resume loops that pump a Microsoft Graph
/// coroutine, routing every yield to the transport stream opened on the
/// Graph origin.
impl<'a, 'local> Client<'a, 'local> {
    /// Runs a Microsoft Graph coroutine to completion, routing every
    /// yield to the transport stream opened on the Graph origin.
    fn run_msgraph<C, T>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<T>, MsgraphSendError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                MsgraphCoroutineState::Complete(Ok(output)) => return Ok(output.response),
                MsgraphCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                MsgraphCoroutineState::Yielded(MsgraphYield::WantsRead) => {
                    arg = Some(self.read(MSGRAPH_API_BASE)?);
                }
                MsgraphCoroutineState::Yielded(MsgraphYield::WantsWrite(bytes)) => {
                    self.write(MSGRAPH_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Drives one Graph contacts delta request, surfacing an expired
    /// delta link (HTTP 410) as [`None`] instead of an error (the
    /// generic [`Self::run_msgraph`] erases the status the fallback
    /// needs).
    fn run_msgraph_delta<C>(
        &mut self,
        mut coroutine: C,
    ) -> Result<Option<MsgraphContactsDeltaResponse>, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<MsgraphContactsDeltaResponse>, MsgraphSendError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                MsgraphCoroutineState::Complete(Ok(output)) => {
                    return Ok(Some(output.response));
                }
                MsgraphCoroutineState::Complete(Err(err)) if err.status() == Some(410) => {
                    return Ok(None);
                }
                MsgraphCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                MsgraphCoroutineState::Yielded(MsgraphYield::WantsRead) => {
                    arg = Some(self.read(MSGRAPH_API_BASE)?);
                }
                MsgraphCoroutineState::Yielded(MsgraphYield::WantsWrite(bytes)) => {
                    self.write(MSGRAPH_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// io-msgraph contact to the JNI-facing card shape: the projected
/// vCard document, the Graph id as both display id and addressing key
/// (uri), and the changeKey as ETag.
fn graph_card(contact: MsgraphContact) -> Card {
    let vcard = msgraph::to_vcard(&contact);
    Card {
        id: contact.id.clone(),
        uri: contact.id,
        etag: contact.change_key,
        vcard,
        books: Vec::new(),
    }
}

fn parse_graph_url(raw: &str) -> Result<Url, BridgeError> {
    Url::parse(raw).map_err(|err| format!("Invalid Graph page URL `{raw}`: {err}").into())
}

/// The `$expand` clause fetching the bridge's stash extended property
/// along with the contact (Graph omits extended properties otherwise).
fn graph_expand() -> String {
    format!(
        "singleValueExtendedProperties($filter=id eq '{}')",
        msgraph::EXTENDED_PROP_ID
    )
}

/// The Graph JSON batching envelope (`POST $batch`).
#[derive(Serialize)]
struct GraphBatch {
    requests: Vec<GraphBatchRequest>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphBatchRequest {
    id: String,
    method: &'static str,
    url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<GraphBatchHeaders>,
    #[serde(skip_serializing_if = "Option::is_none")]
    body: Option<MsgraphContact>,
}

#[derive(Serialize)]
struct GraphBatchHeaders {
    #[serde(rename = "Content-Type")]
    content_type: &'static str,
}

impl GraphBatchHeaders {
    const JSON: Self = Self {
        content_type: "application/json",
    };
}

#[derive(Deserialize)]
struct GraphBatchReplies {
    #[serde(default)]
    responses: Vec<GraphBatchReply>,
}

#[derive(Deserialize)]
struct GraphBatchReply {
    id: String,
    status: u16,
    #[serde(default)]
    body: Option<Value>,
}
