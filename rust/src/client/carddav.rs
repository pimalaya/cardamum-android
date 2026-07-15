//! CardDAV operations: the RFC 6352 addressbook and card verbs, plus the
//! RFC 6578 sync-collection round, run over the WebDAV coroutines.

use std::{borrow::Cow, collections::BTreeSet};

use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_pim_discovery::rfc6764::{types::DavService, well_known::WellKnown};
use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::{GETETAG, WebdavAuth},
    rfc6352::{
        addressbook::{Addressbook as DavAddressbook, list::ListAddressbooks},
        card::{
            CardEntry, CardRef, create::CreateCard, delete::DeleteCard, enumerate::EnumCards,
            list::ListCards, multiget::MultigetCards, read::ReadCard, update::UpdateCard,
        },
    },
    rfc6578::sync_collection::{SyncCollection, SyncCollectionError, SyncDelta},
};
use url::Url;
use vcard::tree::cst::VcardCst;

use crate::{
    client::{Client, USER_AGENT, convert::coroutine_error},
    types::{Addressbook, BridgeError, Card, Credentials},
};

/// CardDAV operations: the RFC 6352 addressbook and card verbs, plus the
/// RFC 6578 sync-collection round, run over the WebDAV coroutines.
impl<'a, 'local> Client<'a, 'local> {
    /// Walks current-user-principal -> addressbook-home-set -> list,
    /// returning the account's addressbooks. Doubles as the connection
    /// check during onboarding: any failure (TLS, auth, discovery walk)
    /// surfaces here.
    pub fn list_carddav_addressbooks(
        &mut self,
        base_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = auth(credentials);

        // NOTE: RFC 6764 §5: a bare origin is not necessarily the
        // context root, so probe .well-known/carddav for the real one
        // (fastmail 404s outside /dav/*); no redirect keeps the origin.
        let base_url = match base_url.path() {
            "" | "/" => {
                let probe = WellKnown::new(base_url.clone(), DavService::Carddav);
                match self.run_discovery(probe) {
                    Ok(Some(root)) => root,
                    _ => base_url.clone(),
                }
            }
            _ => base_url.clone(),
        };
        let base_url = &base_url;

        // NOTE: some servers expose the home-set off the context root,
        // so a missing principal is not fatal.
        let principal = self
            .principal(base_url, &auth)?
            .unwrap_or_else(|| base_url.clone());

        let home_set = self
            .addressbook_home_set(&principal, &auth)?
            .ok_or_else(|| "No addressbook home set found".to_string())?;

        let coroutine = ListAddressbooks::new(&home_set, &auth, USER_AGENT, home_set.path());
        let addressbooks: BTreeSet<DavAddressbook> = self.run(&home_set, coroutine)?;

        Ok(addressbooks
            .into_iter()
            .map(|book| into_addressbook(&home_set, book))
            .collect())
    }

    /// Lists the cards of the addressbook collection at `url`.
    pub fn list_carddav_cards(
        &mut self,
        url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = ListCards::new(url, &auth, USER_AGENT, url.path());
        let cards: BTreeSet<CardEntry> = self.run(url, coroutine)?;

        Ok(cards.into_iter().map(into_card).collect())
    }

    /// Creates the card `id` inside the addressbook collection at
    /// `url`, returning the new ETag when the server sent one.
    pub fn create_carddav_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        id: &str,
        vcard: &str,
    ) -> Result<Option<String>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = CreateCard::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            normalize_vcard(vcard).as_bytes().to_vec(),
        );

        Ok(self.run(url, coroutine)?.etag)
    }

    /// Reads the card at resource name `uri` (as the server returned
    /// it) inside the addressbook collection at `url`, returning its
    /// body and ETag.
    pub fn read_carddav_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uri: &str,
    ) -> Result<Card, BridgeError> {
        let auth = auth(credentials);
        let coroutine = ReadCard::new(url, &auth, USER_AGENT, url.path(), uri);
        let body = self.run(url, coroutine)?;

        Ok(Card {
            id: uri.trim_end_matches(".vcf").to_string(),
            uri: uri.to_string(),
            etag: body.etag,
            vcard: String::from_utf8_lossy(&body.data).into_owned(),
            books: Vec::new(),
        })
    }

    /// Updates the card at resource name `uri` (as the server returned
    /// it) inside the addressbook collection at `url`, guarded by
    /// `if_match` when an ETag is known, returning the new ETag when
    /// the server sent one.
    pub fn update_carddav_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uri: &str,
        vcard: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = UpdateCard::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            uri,
            normalize_vcard(vcard).as_bytes().to_vec(),
            if_match,
        );

        Ok(self.run(url, coroutine)?.etag)
    }

    /// Deletes the card at resource name `uri` (as the server returned
    /// it) inside the addressbook collection at `url`, guarded by
    /// `if_match` when an ETag is known.
    pub fn delete_carddav_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uri: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = auth(credentials);
        let coroutine = DeleteCard::new(url, &auth, USER_AGENT, url.path(), uri, if_match);
        self.run(url, coroutine)?;

        Ok(())
    }

    /// Enumerates the card spine (resource name plus ETag, no body) of
    /// the addressbook collection at `url`.
    pub fn enum_cards(
        &mut self,
        url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<CardRef>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = EnumCards::new(url, &auth, USER_AGENT, url.path());
        let refs: BTreeSet<CardRef> = self.run(url, coroutine)?;

        Ok(refs.into_iter().collect())
    }

    /// Runs a `sync-collection` REPORT (RFC 6578) against the
    /// addressbook collection at `url`, draining truncated result sets.
    /// Returns [`None`] when the server rejected the sync token, so the
    /// caller falls back to a full enumeration.
    pub fn sync_carddav_cards(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        sync_token: Option<&str>,
    ) -> Result<Option<SyncDelta>, BridgeError> {
        let auth = auth(credentials);
        let mut token = sync_token.map(str::to_string);
        let mut delta = SyncDelta::default();

        loop {
            let coroutine = SyncCollection::new(
                url,
                &auth,
                USER_AGENT,
                url.path(),
                token.as_deref(),
                &[GETETAG],
            );
            let page = match self.run_sync_collection(url, coroutine)? {
                Some(page) => page,
                None => return Ok(None),
            };

            delta.changed.extend(page.changed);
            delta.vanished.extend(page.vanished);
            delta.sync_token = page.sync_token;

            if !page.truncated {
                return Ok(Some(delta));
            }
            token = delta.sync_token.clone();
        }
    }

    /// Batch-fetches the cards at the given resource names (as the
    /// server returned them) inside the addressbook collection at `url`
    /// via REPORT `addressbook-multiget`.
    pub fn multiget_cards(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uris: &[&str],
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = MultigetCards::new(url, &auth, USER_AGENT, url.path(), uris);
        let cards: Vec<CardEntry> = self.run(url, coroutine)?;

        Ok(cards.into_iter().map(into_card).collect())
    }

    /// Drives one `sync-collection` REPORT, surfacing a rejected sync
    /// token as [`None`] instead of an error (the generic [`Self::run`]
    /// erases the error variant the fallback needs).
    fn run_sync_collection(
        &mut self,
        target: &Url,
        mut coroutine: SyncCollection,
    ) -> Result<Option<SyncDelta>, BridgeError> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(delta)) => return Ok(Some(delta)),
                WebdavCoroutineState::Complete(Err(SyncCollectionError::InvalidSyncToken)) => {
                    return Ok(None);
                }
                WebdavCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(bytes)) => {
                    self.write(target.as_str(), &bytes)?;
                    arg = None;
                }
                WebdavCoroutineState::Yielded(WebdavYield::WantsRead) => {
                    arg = Some(self.read(target.as_str())?);
                }
            }
        }
    }
}

/// Auth scheme from the credentials: an empty login means the
/// password field carries an OAuth 2.0 access token (Bearer, RFC
/// 6750); otherwise HTTP Basic, the only password scheme CardDAV
/// servers negotiate in practice.
fn auth(credentials: &Credentials) -> WebdavAuth {
    if credentials.login.is_empty() {
        WebdavAuth::Bearer(HttpAuthBearer::new(credentials.password))
    } else {
        WebdavAuth::Basic(HttpAuthBasic::new(credentials.login, credentials.password))
    }
}

/// Ensures an outgoing vCard carries the mandatory properties its version
/// requires, so strict CardDAV servers accept it.
///
/// A vCard 3.0 without the required `N` is rejected by iCloud and Fastmail
/// with a parse error; contacts entered with only a display name have `FN`
/// but no `N`. This delegates to vcard-rs's spec-driven, byte-preserving
/// repair (`VcardCst::fill_required`), which supplies the missing empty
/// `N:;;;;` (and any other required property) and leaves a valid card
/// untouched. A body that will not parse is sent as-is rather than
/// dropped.
pub(crate) fn normalize_vcard(vcard: &str) -> Cow<'_, str> {
    match VcardCst::parse(vcard) {
        Ok(mut card) => Cow::Owned(card.fill_required().to_string()),
        Err(_) => Cow::Borrowed(vcard),
    }
}

/// io-webdav addressbook to the JNI-facing shape: the display name
/// defaults to the id when the server returned none, and the absolute
/// collection URL is rebuilt from the home set (io-webdav only keeps
/// the last path segment).
fn into_addressbook(home_set: &Url, book: DavAddressbook) -> Addressbook {
    let mut url = home_set.clone();
    url.set_path(&format!(
        "{}/{}/",
        home_set.path().trim_end_matches('/'),
        book.id
    ));

    Addressbook {
        name: book.display_name.unwrap_or_else(|| book.id.clone()),
        id: book.id,
        url: url.to_string(),
        description: book.description,
        color: book.color,
    }
}

/// io-webdav card entry to the JNI-facing shape; vCards are text, so
/// the raw bytes are decoded lossily.
fn into_card(entry: CardEntry) -> Card {
    Card {
        vcard: String::from_utf8_lossy(&entry.data).into_owned(),
        id: entry.id,
        uri: entry.uri,
        etag: entry.etag,
        books: Vec::new(),
    }
}
