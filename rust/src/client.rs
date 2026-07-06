//! Blocking CardDAV client over the JNI transport: drives pimconf's
//! RFC 6764 discovery and io-webdav's sans-io CardDAV coroutines, doing
//! all socket I/O by upcalling the Java `Transport` on each yield. TLS
//! lives in Java; this crate only ever sees plaintext bytes.
//!
//! Session state is not cached: each native call builds a client, runs
//! one operation, and drops it. The Java transport owns the sockets,
//! one per origin (DNS resolver, HTTPS servers), so a single discovery
//! or CardDAV cycle can talk to several endpoints; the bridge only
//! tells it which URL each yield wants.

use core::fmt::Display;
use std::{
    borrow::Cow,
    collections::{BTreeMap, BTreeSet},
};

use io_http::{
    rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic, rfc9110::request::HttpRequest,
};
use io_jmap::{
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{JmapSession, coroutine::JmapRedirectYield, session_get::*},
    rfc9610::{
        address_book::{JmapAddressBook, get::*},
        contact_card::{
            JmapContactCard, JmapContactCardFilter, JmapContactCardPatch, get::*, query::*, set::*,
        },
    },
};
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::users::{
            contact_folders::{MsgraphContactFoldersListResponse, list::MsgraphContactFoldersList},
            contacts::{
                MsgraphContact, MsgraphContactsListResponse,
                create::MsgraphContactCreate,
                delete::MsgraphContactDelete,
                get::MsgraphContactGet,
                list::{MsgraphContactsList, MsgraphContactsListParams},
                update::MsgraphContactUpdate,
            },
        },
        send::{MSGRAPH_API_BASE, MsgraphSend, MsgraphSendError, MsgraphSendOutput},
    },
};
use io_oauth::v2_0::{
    authorization_code_grant::access_token_request::{
        Oauth20AccessTokenRequestParams, Oauth20RequestAccessToken, Oauth20RequestAccessTokenResult,
    },
    issue_access_token::{
        Oauth20IssueAccessTokenErrorParams, Oauth20IssueAccessTokenSuccessParams,
    },
    refresh_access_token::{
        Oauth20RefreshAccessToken, Oauth20RefreshAccessTokenParams, Oauth20RefreshAccessTokenResult,
    },
};
use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::{WebdavAuth, coroutine::WebdavRedirectYield},
    rfc5397::current_user_principal::CurrentUserPrincipal,
    rfc6352::{
        addressbook::{
            Addressbook as DavAddressbook, home_set::AddressbookHomeSet, list::ListAddressbooks,
        },
        card::{
            CardEntry, create::CreateCard, delete::DeleteCard, list::ListCards, read::ReadCard,
            update::UpdateCard,
        },
    },
};
use jni::{
    Env, JValue,
    errors::Error,
    jni_sig, jni_str,
    objects::{JByteArray, JObject},
};
use pimconf::{
    coroutine::{DiscoveryCoroutine, DiscoveryCoroutineState, DiscoveryYield},
    rfc6764::{resolve::ResolveDav, types::DavService, well_known::WellKnown},
    search::{
        all::{SearchAll, SearchError},
        first::SearchFirst,
        types::{Service, ServiceConfig},
    },
};
use secrecy::SecretString;
use url::Url;

use crate::{
    jmap, msgraph,
    oauth::parse_pkce_verifier,
    types::{Addressbook, Card, Credentials},
};

/// RFC 8484 DNS-over-HTTPS resolver used for the discovery DNS lookups
/// (SRV, TXT, MX) when the caller passed none. DoH rides the same TLS
/// transport as every other request, so it works on mobile networks
/// that block outbound DNS over TCP.
const DNS_RESOLVER: &str = "https://cloudflare-dns.com/dns-query";

/// Sent as the `User-Agent` on every WebDAV request.
const USER_AGENT: &str = concat!("cardamum-android/", env!("CARGO_PKG_VERSION"));

/// One native call's CardDAV client: a mutable `Env` and the Java
/// transport it upcalls for socket I/O.
pub struct Client<'a, 'local> {
    env: &'a mut Env<'local>,
    transport: &'a JObject<'local>,
}

impl<'a, 'local> Client<'a, 'local> {
    /// Wraps the JNI context for one native call.
    pub fn new(env: &'a mut Env<'local>, transport: &'a JObject<'local>) -> Self {
        Self { env, transport }
    }

    // ---- Operations -------------------------------------------------------

    /// Resolves the email's domain to a CardDAV context root via
    /// RFC 6764: SRV and TXT over the given resolver (`tcp://host:port`,
    /// or an RFC 8484 `https://…/dns-query` one; DoH by default), then
    /// `.well-known`. When the resolver is unreachable it retries with
    /// the `.well-known` probe alone, whose HTTPS socket resolves the
    /// domain through the OS.
    pub fn discover(&mut self, email: &str, resolver: Option<&str>) -> Result<Url, String> {
        let domain = email.rsplit('@').next().unwrap_or(email).trim();

        if domain.is_empty() {
            return Err("Email address is missing a domain".to_string());
        }

        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        let resolver = Url::parse(resolver)
            .map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}"))?;

        let coroutine = ResolveDav::new(domain, DavService::Carddav, resolver);
        let srv_err = match self.run_discovery(coroutine) {
            Ok(url) => return Ok(url),
            Err(err) => err,
        };

        let origin = format!("https://{domain}/");
        let origin =
            Url::parse(&origin).map_err(|err| format!("Invalid origin URL `{origin}`: {err}"))?;

        let coroutine = WellKnown::new(origin.clone(), DavService::Carddav);
        match self.run_discovery(coroutine) {
            // RFC 6764 §5: no redirect means nothing authoritative on
            // the origin; keep the origin itself as context root.
            Ok(redirect) => Ok(redirect.unwrap_or(origin)),
            Err(err) => Err(format!("{err} (SRV/TXT lookup failed first: {srv_err})")),
        }
    }

    /// Searches every pimconf mechanism for CardDAV and JMAP service
    /// configs: fixed provider rules (Google/Microsoft, matched by
    /// domain then by MX records), PACC, the RFC 6764 CardDAV resolve
    /// and the RFC 8620 JMAP resolve. Each config carries its endpoint
    /// and authentication methods (password, OAuth 2.0 grants). With
    /// `first`, stops at the first mechanism yielding a config.
    pub fn search(
        &mut self,
        email: &str,
        resolver: Option<&str>,
        first: bool,
    ) -> Result<Vec<ServiceConfig>, String> {
        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        let resolver = Url::parse(resolver)
            .map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}"))?;

        let services = BTreeSet::from([Service::Carddav, Service::Jmap]);

        if first {
            let coroutine =
                SearchFirst::new(email, services, resolver).map_err(|err| err.to_string())?;
            self.run_search(coroutine)
        } else {
            let coroutine =
                SearchAll::new(email, services, resolver).map_err(|err| err.to_string())?;
            self.run_search(coroutine)
        }
    }

    /// Exchanges an authorization code for OAuth 2.0 tokens against
    /// the token endpoint (RFC 6749 §4.1.3), carrying the PKCE
    /// verifier of the authorization request and the client secret
    /// when the registration issued one (Google desktop clients).
    pub fn oauth_request_access_token(
        &mut self,
        token_endpoint: &Url,
        client_id: &str,
        client_secret: Option<&str>,
        code: &str,
        redirect_uri: &str,
        pkce_verifier: &str,
    ) -> Result<Oauth20IssueAccessTokenSuccessParams, String> {
        let verifier = parse_pkce_verifier(pkce_verifier)?;
        let params = Oauth20AccessTokenRequestParams {
            code: code.into(),
            redirect_uri: Some(redirect_uri.into()),
            client_id: client_id.into(),
            client_secret: client_secret.map(SecretString::from),
            pkce_code_verifier: Some(Cow::Owned(verifier)),
        };

        let mut coroutine =
            Oauth20RequestAccessToken::new(oauth_post_request(token_endpoint), params);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20RequestAccessTokenResult::Ok(Ok(success)) => return Ok(success),
                Oauth20RequestAccessTokenResult::Ok(Err(err)) => return Err(oauth_error(err)),
                Oauth20RequestAccessTokenResult::Err(err) => return Err(err.to_string()),
                Oauth20RequestAccessTokenResult::WantsRead => {
                    arg = Some(self.read(token_endpoint.as_str())?);
                }
                Oauth20RequestAccessTokenResult::WantsWrite(bytes) => {
                    self.write(token_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Refreshes OAuth 2.0 tokens against the token endpoint
    /// (RFC 6749 §6). `scope` is the space-separated scope list of
    /// the original grant (empty keeps the server default); the client
    /// secret rides along when the registration issued one.
    pub fn oauth_refresh_access_token(
        &mut self,
        token_endpoint: &Url,
        client_id: &str,
        client_secret: Option<&str>,
        refresh_token: &str,
        scope: &str,
    ) -> Result<Oauth20IssueAccessTokenSuccessParams, String> {
        let mut params = Oauth20RefreshAccessTokenParams::new(client_id, refresh_token);
        params.client_secret = client_secret.map(SecretString::from);
        params.scopes = scope.split_whitespace().map(Cow::Borrowed).collect();

        let mut coroutine =
            Oauth20RefreshAccessToken::new(oauth_post_request(token_endpoint), params);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20RefreshAccessTokenResult::Ok(Ok(success)) => return Ok(success),
                Oauth20RefreshAccessTokenResult::Ok(Err(err)) => return Err(oauth_error(err)),
                Oauth20RefreshAccessTokenResult::Err(err) => return Err(err.to_string()),
                Oauth20RefreshAccessTokenResult::WantsRead => {
                    arg = Some(self.read(token_endpoint.as_str())?);
                }
                Oauth20RefreshAccessTokenResult::WantsWrite(bytes) => {
                    self.write(token_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Walks current-user-principal -> addressbook-home-set -> list,
    /// returning the account's addressbooks. Doubles as the connection
    /// check during onboarding: any failure (TLS, auth, discovery walk)
    /// surfaces here.
    pub fn list_addressbooks(
        &mut self,
        base_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, String> {
        let auth = auth(credentials);

        // RFC 6764 §5: a bare origin (e.g. the carddav.example.com a
        // PACC document advertises) is not necessarily the context
        // root; probe .well-known/carddav for the real one first
        // (fastmail 404s every request outside /dav/*). No redirect or
        // a failed probe keeps the origin, which plenty of servers
        // serve directly.
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

        // Some servers expose the home-set straight off the context
        // root, so a missing principal is not fatal.
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
    pub fn list_cards(
        &mut self,
        url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, String> {
        let auth = auth(credentials);
        let coroutine = ListCards::new(url, &auth, USER_AGENT, url.path());
        let cards: BTreeSet<CardEntry> = self.run(url, coroutine)?;

        Ok(cards.into_iter().map(into_card).collect())
    }

    /// Creates the card `id` inside the addressbook collection at
    /// `url`, returning the new ETag when the server sent one.
    pub fn create_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        id: &str,
        vcard: &str,
    ) -> Result<Option<String>, String> {
        let auth = auth(credentials);
        let coroutine = CreateCard::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            vcard.as_bytes().to_vec(),
        );

        Ok(self.run(url, coroutine)?.etag)
    }

    /// Reads the card at resource name `uri` (as the server returned
    /// it) inside the addressbook collection at `url`, returning its
    /// body and ETag.
    pub fn read_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uri: &str,
    ) -> Result<Card, String> {
        let auth = auth(credentials);
        let coroutine = ReadCard::new(url, &auth, USER_AGENT, url.path(), uri);
        let body = self.run(url, coroutine)?;

        Ok(Card {
            id: uri.trim_end_matches(".vcf").to_string(),
            uri: uri.to_string(),
            etag: body.etag,
            vcard: String::from_utf8_lossy(&body.data).into_owned(),
        })
    }

    /// Updates the card at resource name `uri` (as the server returned
    /// it) inside the addressbook collection at `url`, guarded by
    /// `if_match` when an ETag is known, returning the new ETag when
    /// the server sent one.
    pub fn update_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uri: &str,
        vcard: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, String> {
        let auth = auth(credentials);
        let coroutine = UpdateCard::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            uri,
            vcard.as_bytes().to_vec(),
            if_match,
        );

        Ok(self.run(url, coroutine)?.etag)
    }

    /// Deletes the card at resource name `uri` (as the server returned
    /// it) inside the addressbook collection at `url`, guarded by
    /// `if_match` when an ETag is known.
    pub fn delete_card(
        &mut self,
        url: &Url,
        credentials: &Credentials,
        uri: &str,
        if_match: Option<&str>,
    ) -> Result<(), String> {
        let auth = auth(credentials);
        let coroutine = DeleteCard::new(url, &auth, USER_AGENT, url.path(), uri, if_match);
        self.run(url, coroutine)?;

        Ok(())
    }

    // ---- Microsoft Graph operations -----------------------------------------

    /// Lists the Microsoft Graph contact folders as addressbooks,
    /// prepending the default Contacts folder (Graph serves it outside
    /// the folder list, addressed by an empty folder id). Collection
    /// URLs are left empty: the caller composes them, since only it
    /// knows the account they belong to.
    pub fn list_graph_addressbooks(&mut self, token: &str) -> Result<Vec<Addressbook>, String> {
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
    pub fn list_graph_cards(&mut self, token: &str, folder: &str) -> Result<Vec<Card>, String> {
        let auth = HttpAuthBearer::new(token);
        let folder = (!folder.is_empty()).then_some(folder);

        let params = MsgraphContactsListParams {
            top: Some(100),
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
    ) -> Result<Card, String> {
        let contact = msgraph::to_new_contact(vcard)?;
        let auth = HttpAuthBearer::new(token);
        let folder = (!folder.is_empty()).then_some(folder);

        let coroutine = MsgraphContactCreate::new(&auth, "me", folder, &contact)
            .map_err(|err| err.to_string())?;
        Ok(graph_card(self.run_msgraph(coroutine)?))
    }

    /// Reads the Graph contact `id`, projected onto a vCard document.
    pub fn read_graph_card(&mut self, token: &str, id: &str) -> Result<Card, String> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = MsgraphContactGet::new(&auth, "me", id).map_err(|err| err.to_string())?;
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
    ) -> Result<Card, String> {
        let contact = match base_vcard {
            Some(base) => msgraph::to_contact_delta(vcard, base)?,
            None => msgraph::to_new_contact(vcard)?,
        };
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphContactUpdate::new(&auth, "me", id, &contact).map_err(|err| err.to_string())?;
        Ok(graph_card(self.run_msgraph(coroutine)?))
    }

    /// Deletes the Graph contact `id`.
    pub fn delete_graph_card(&mut self, token: &str, id: &str) -> Result<(), String> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphContactDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;

        Ok(())
    }

    // ---- JMAP operations ----------------------------------------------------

    /// Lists the account's JMAP AddressBooks (RFC 9610 §2.1).
    /// Collection URLs are left empty: the caller composes them, since
    /// only it knows the account they belong to. Doubles as the
    /// connection check during onboarding.
    pub fn list_jmap_addressbooks(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, String> {
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

    /// Lists the ContactCards of the AddressBook `book_id`, each
    /// converted to a vCard document; the ContactCard id is the
    /// addressing key and a JSON hash the ETag.
    pub fn list_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        book_id: &str,
    ) -> Result<Vec<Card>, String> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardQueryOptions {
            filter: Some(JmapContactCardFilter {
                in_address_book: Some(book_id.to_string()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        out.cards.into_iter().map(jmap::to_card).collect()
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
    ) -> Result<Card, String> {
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
            return Err(format!("JMAP ContactCard create rejected: {err:?}"));
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
        })
    }

    /// Reads the ContactCard `id`, converted to a vCard document.
    pub fn read_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<Card, String> {
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

        jmap::to_card(card)
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
    ) -> Result<Card, String> {
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
            return Err(format!("JMAP ContactCard update rejected: {err:?}"));
        }

        Ok(Card {
            id: id.to_string(),
            uri: id.to_string(),
            etag: None,
            vcard: vcard.to_string(),
        })
    }

    /// Destroys the ContactCard `id`.
    pub fn delete_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<(), String> {
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
            return Err(format!("JMAP ContactCard destroy rejected: {err:?}"));
        }

        Ok(())
    }

    // ---- Discovery walk steps ---------------------------------------------

    /// PROPFIND the context root for `DAV:current-user-principal`.
    fn principal(&mut self, base_url: &Url, auth: &WebdavAuth) -> Result<Option<Url>, String> {
        self.run_redirect(base_url, |url| {
            CurrentUserPrincipal::new(url, auth, USER_AGENT)
        })
    }

    /// PROPFIND the principal for `CARDDAV:addressbook-home-set`.
    fn addressbook_home_set(
        &mut self,
        principal: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, String> {
        self.run_redirect(principal, |url| {
            AddressbookHomeSet::new(url, auth, USER_AGENT, url.path())
        })
    }

    // ---- Coroutine runners ------------------------------------------------

    /// Drives a discovery coroutine to completion, routing each yield
    /// to the stream the Java transport opens for the yielded URL.
    fn run_discovery<C, T, E>(&mut self, mut coroutine: C) -> Result<T, String>
    where
        C: DiscoveryCoroutine<Yield = DiscoveryYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(url)) => return Ok(url),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsRead { url }) => {
                    arg = Some(self.read(url.as_str())?);
                }
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsWrite { url, bytes }) => {
                    self.write(url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Drives a search coroutine, converting transport failures on
    /// one endpoint into an EOF signal (an empty resume slice) so the
    /// failing mechanism errors out and the search moves on to the
    /// next one, mirroring pimconf's own std search client.
    fn run_search<C>(&mut self, mut coroutine: C) -> Result<Vec<ServiceConfig>, String>
    where
        C: DiscoveryCoroutine<
                Yield = DiscoveryYield,
                Return = Result<Vec<ServiceConfig>, SearchError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(configs)) => return Ok(configs),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsRead { url }) => {
                    arg = Some(match self.read(url.as_str()) {
                        Ok(bytes) => bytes,
                        Err(_) => Vec::new(),
                    });
                }
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsWrite { url, bytes }) => {
                    arg = match self.write(url.as_str(), &bytes) {
                        Ok(()) => None,
                        Err(_) => Some(Vec::new()),
                    };
                }
            }
        }
    }

    /// Runs a Microsoft Graph coroutine to completion, routing every
    /// yield to the transport stream opened on the Graph origin.
    fn run_msgraph<C, T>(&mut self, mut coroutine: C) -> Result<T, String>
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
                MsgraphCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
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

    /// Fetches the JMAP session (RFC 8620 §2) from the session URL
    /// (a bare origin triggers /.well-known/jmap discovery), rebuilding
    /// the coroutine whenever the server answers 3xx.
    fn jmap_session(
        &mut self,
        session_url: &Url,
        http_auth: &SecretString,
    ) -> Result<JmapSession, String> {
        let mut target = session_url.clone();

        loop {
            let mut coroutine = JmapSessionGet::new(http_auth, &target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    JmapCoroutineState::Complete(Ok(out)) => return Ok(out.session),
                    JmapCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
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
    fn run_jmap<C, T, E>(&mut self, api_url: &Url, mut coroutine: C) -> Result<T, String>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(value)) => return Ok(value),
                JmapCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
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

    /// Drives a plain (non-redirect) coroutine against `target`.
    fn run<C, T, E>(&mut self, target: &Url, mut coroutine: C) -> Result<T, String>
    where
        C: WebdavCoroutine<Yield = WebdavYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(value)) => return Ok(value),
                WebdavCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
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

    /// Drives a redirect-aware coroutine, rebuilding it against the
    /// new target whenever the server answers 3xx; the transport pools
    /// one connection per origin, so a cross-origin redirect just
    /// opens a new socket.
    fn run_redirect<C, T, E>(&mut self, start: &Url, make: impl Fn(&Url) -> C) -> Result<T, String>
    where
        C: WebdavCoroutine<Yield = WebdavRedirectYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut target = start.clone();

        loop {
            let mut coroutine = make(&target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    WebdavCoroutineState::Complete(Ok(value)) => return Ok(value),
                    WebdavCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
                    WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsWrite(bytes)) => {
                        self.write(target.as_str(), &bytes)?;
                        arg = None;
                    }
                    WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsRead) => {
                        arg = Some(self.read(target.as_str())?);
                    }
                    WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsRedirect {
                        url,
                        ..
                    }) => {
                        // Rebuild the coroutine against the new target;
                        // the auth is carried over by `make`.
                        target = url;
                        break;
                    }
                }
            }
        }
    }

    // ---- Transport upcalls ------------------------------------------------

    /// Upcalls the Java transport to read the next chunk from the
    /// stream open on `url`.
    fn read(&mut self, url: &str) -> Result<Vec<u8>, String> {
        // NOTE: an empty slice signals EOF to the coroutine.
        let url = self.env.new_string(url).map_err(|err| err.to_string())?;
        let value = self
            .env
            .call_method(
                self.transport,
                jni_str!("read"),
                jni_sig!("(Ljava/lang/String;)[B"),
                &[JValue::Object(&url)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport read", err))?;
        let object = value.l().map_err(|err| err.to_string())?;
        let array = unsafe { JByteArray::from_raw(self.env, object.into_raw()) };

        self.env
            .convert_byte_array(&array)
            .map_err(|err| err.to_string())
    }

    /// Upcalls the Java transport to write and flush bytes to the
    /// stream open on `url`.
    fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), String> {
        let url = self.env.new_string(url).map_err(|err| err.to_string())?;
        let array = self
            .env
            .byte_array_from_slice(bytes)
            .map_err(|err| err.to_string())?;

        self.env
            .call_method(
                self.transport,
                jni_str!("write"),
                jni_sig!("(Ljava/lang/String;[B)V"),
                &[JValue::Object(&url), JValue::Object(&array)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport write", err))?;

        Ok(())
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

/// Authorization header value from the credentials, following the same
/// convention as [`auth`]: an empty login means the password field
/// carries an OAuth 2.0 access token (Bearer), otherwise HTTP Basic.
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

/// POST request skeleton against the token endpoint, Host header
/// included (the io-oauth coroutines add the Content-Type and body).
fn oauth_post_request(endpoint: &Url) -> HttpRequest {
    let host = endpoint.host_str().unwrap_or("");
    let port = endpoint.port_or_known_default().unwrap_or(443);

    HttpRequest {
        method: "POST".into(),
        url: endpoint.clone(),
        headers: Vec::new(),
        body: Vec::new(),
    }
    .header("Host", format!("{host}:{port}"))
}

/// Formats an RFC 6749 §5.2 token error response.
fn oauth_error(err: Oauth20IssueAccessTokenErrorParams) -> String {
    match err.error_description {
        Some(description) => format!("OAuth error {:?}: {description}", err.error),
        None => format!("OAuth error {:?}", err.error),
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
    }
}

/// Parses an OData paging link served by Graph.
fn parse_graph_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid Graph page URL `{raw}`: {err}"))
}

/// io-webdav card entry to the JNI-facing shape; vCards are text, so
/// the raw bytes are decoded lossily.
fn into_card(entry: CardEntry) -> Card {
    Card {
        vcard: String::from_utf8_lossy(&entry.data).into_owned(),
        id: entry.id,
        uri: entry.uri,
        etag: entry.etag,
    }
}

/// Catches and clears any pending Java exception, surfacing its class
/// and message (a bare JNI error only says "Java exception was thrown").
fn clear_and_fail(env: &mut Env, op: &str, err: Error) -> String {
    match env.exception_catch() {
        Err(Error::CaughtJavaException { name, msg, .. }) => {
            format!("{op} failed: {name}: {msg}")
        }
        _ => format!("{op} failed: {err}"),
    }
}
