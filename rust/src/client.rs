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

use io_google_people::{
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
use io_http::{
    rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic, rfc9110::request::HttpRequest,
};
use io_jmap::{
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{
        JmapMethodError, JmapSession, changes::*, coroutine::JmapRedirectYield, session_get::*,
    },
    rfc9610::{
        address_book::{JmapAddressBook, get::*},
        contact_card::{
            JmapContactCard, JmapContactCardPatch, changes::*, get::*, query::*, set::*,
        },
    },
};
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
    rfc7591::client_registration::{
        Oauth20ClientInformation, Oauth20RegisterClient, Oauth20RegisterClientParams,
        Oauth20RegisterClientResult,
    },
    rfc8414::server_metadata::{
        Oauth20FetchServerMetadata, Oauth20FetchServerMetadataResult, Oauth20ServerMetadata,
    },
};
use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::{GETETAG, WebdavAuth, coroutine::WebdavRedirectYield},
    rfc5397::current_user_principal::CurrentUserPrincipal,
    rfc6352::{
        addressbook::{
            Addressbook as DavAddressbook, home_set::AddressbookHomeSet, list::ListAddressbooks,
        },
        card::{
            CardEntry, CardRef, create::CreateCard, delete::DeleteCard, enumerate::EnumCards,
            list::ListCards, multiget::MultigetCards, read::ReadCard, update::UpdateCard,
        },
    },
    rfc6578::sync_collection::{SyncCollection, SyncCollectionError, SyncDelta},
};
use jni::{
    Env, JValue,
    errors::Error,
    jni_sig, jni_str,
    objects::{JByteArray, JObject},
};
use pimconf::{
    autoconfig::mx::DiscoveryDnsMx,
    coroutine::{DiscoveryCoroutine, DiscoveryCoroutineState, DiscoveryYield},
    pacc::discover::DiscoveryPacc,
    rfc6764::{resolve::ResolveDav, types::DavService, well_known::WellKnown},
    rfc8620::resolve::ResolveJmap,
    rfc9110::ProbeAuth,
    search::{
        providers::Provider,
        types::{Service, ServiceConfig},
    },
};
use secrecy::SecretString;
use url::Url;

use crate::{
    account::{self, Backend},
    google, jmap, msgraph,
    oauth::parse_pkce_verifier,
    types::{Addressbook, Card, CardDelta, Credentials},
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

    /// Matches the email's (or domain's) MX records against the fixed
    /// provider rules, catching every domain whose mail lives at
    /// Google or Microsoft (gmail.com and outlook.com included: their
    /// own exchanges match too, so no separate domain list is
    /// needed). Returns the provider's fixed configs, empty when no
    /// rule matched.
    pub fn search_provider(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<ServiceConfig>, String> {
        let (email, domain) = search_domain(email)?;

        let mx = DiscoveryDnsMx::new(&domain, self.search_resolver(resolver)?);
        let records = self.run_mechanism(mx)?;

        for record in records {
            let exchange = record.data().exchange().to_string();

            if let Some(provider) = Provider::from_mx(&exchange) {
                let mut configs = provider.configs(&email);

                // A bare-domain search carries no email; the username
                // hint is dropped rather than minted from the domain.
                if email.is_empty() {
                    for config in &mut configs {
                        config.username = None;
                    }
                }

                return Ok(configs);
            }
        }

        Ok(Vec::new())
    }

    /// Discovers the email domain's PACC document and flattens it
    /// into service configs.
    pub fn search_pacc(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<ServiceConfig>, String> {
        let (_, domain) = search_domain(email)?;

        let pacc = DiscoveryPacc::new(&domain, self.search_resolver(resolver)?)
            .map_err(|err| err.to_string())?;
        let config = self.run_mechanism(pacc)?;

        Ok(ServiceConfig::from_pacc(&config))
    }

    /// Resolves the email domain's CardDAV context root (RFC 6764)
    /// into a service config.
    pub fn search_carddav(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<ServiceConfig>, String> {
        let (_, domain) = search_domain(email)?;

        let resolve = ResolveDav::new(
            &domain,
            DavService::Carddav,
            self.search_resolver(resolver)?,
        );
        let url = self.run_mechanism(resolve)?;

        Ok(vec![ServiceConfig::from_dav(Service::Carddav, url)])
    }

    /// Resolves the email domain's JMAP session URL (RFC 8620) into a
    /// service config.
    pub fn search_jmap(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<ServiceConfig>, String> {
        let (_, domain) = search_domain(email)?;

        let resolve = ResolveJmap::new(&domain, self.search_resolver(resolver)?);
        let session = self.run_mechanism(resolve)?;

        Ok(vec![ServiceConfig::from_jmap(
            session.url,
            &session.auth_schemes,
        )])
    }

    /// Probes one config's endpoints for the authentication schemes
    /// they advertise on their unauthenticated 401 (PACC §5.4.2) and
    /// refines the config's password and bearer methods accordingly.
    /// The URLs are tried in order until one advertises any scheme; a
    /// probe that fails or learns nothing leaves the config as
    /// discovered.
    pub fn search_probe(&mut self, mut config: ServiceConfig) -> Result<ServiceConfig, String> {
        for url in config.probe_urls() {
            match self.run_mechanism(ProbeAuth::new(url)) {
                Ok(schemes) if !schemes.is_empty() => {
                    config.refine_auth(&schemes);
                    break;
                }
                // NOTE: a probe that fails or learns nothing is
                // best-effort; the config's next URL gets its turn.
                Ok(_) | Err(_) => {}
            }
        }

        Ok(config)
    }

    /// The DNS resolver URL for the search mechanisms, DoH by default.
    fn search_resolver(&self, resolver: Option<&str>) -> Result<Url, String> {
        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        Url::parse(resolver).map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}"))
    }

    /// Fetches an authorization server's RFC 8414 metadata from its
    /// issuer, trying the `oauth-authorization-server` well-known
    /// first and falling back to the OpenID Connect Discovery
    /// document (the mailmaint OAuth draft requires servers to serve
    /// at least one).
    pub fn oauth_server_metadata(&mut self, issuer: &Url) -> Result<Oauth20ServerMetadata, String> {
        let primary = Oauth20ServerMetadata::well_known_url(issuer);
        match self.run_fetch_metadata(&primary) {
            Ok(metadata) => Ok(metadata),
            Err(primary_err) => {
                let fallback = Oauth20ServerMetadata::openid_well_known_url(issuer);
                self.run_fetch_metadata(&fallback).map_err(|fallback_err| {
                    format!("{primary_err} (OpenID fallback also failed: {fallback_err})")
                })
            }
        }
    }

    fn run_fetch_metadata(&mut self, url: &Url) -> Result<Oauth20ServerMetadata, String> {
        let mut coroutine = Oauth20FetchServerMetadata::new(oauth_get_request(url));
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20FetchServerMetadataResult::Ok(metadata) => return Ok(metadata),
                Oauth20FetchServerMetadataResult::Err(err) => return Err(err.to_string()),
                Oauth20FetchServerMetadataResult::WantsRead => {
                    arg = Some(self.read(url.as_str())?);
                }
                Oauth20FetchServerMetadataResult::WantsWrite(bytes) => {
                    self.write(url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Registers a public client at an RFC 7591 registration endpoint
    /// (no secret, `token_endpoint_auth_method: none`), returning the
    /// server-issued client information.
    pub fn oauth_register_client(
        &mut self,
        registration_endpoint: &Url,
        params: &Oauth20RegisterClientParams,
    ) -> Result<Oauth20ClientInformation, String> {
        let request = oauth_post_request(registration_endpoint);
        let mut coroutine =
            Oauth20RegisterClient::new(request, params).map_err(|err| err.to_string())?;
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20RegisterClientResult::Ok(Ok(client)) => return Ok(client),
                Oauth20RegisterClientResult::Ok(Err(err)) => {
                    let detail = err
                        .error_description
                        .unwrap_or_else(|| format!("{:?}", err.error));
                    return Err(format!("Client registration rejected: {detail}"));
                }
                Oauth20RegisterClientResult::Err(err) => return Err(err.to_string()),
                Oauth20RegisterClientResult::WantsRead => {
                    arg = Some(self.read(registration_endpoint.as_str())?);
                }
                Oauth20RegisterClientResult::WantsWrite(bytes) => {
                    self.write(registration_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
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

    // ---- Backend dispatch ---------------------------------------------------

    /// Lists the account's addressbooks, each carrying the absolute
    /// collection URL every card operation targets: the CardDAV
    /// discovery walk, the Graph contact folders, the JMAP
    /// AddressBooks or the Google contact groups. Doubles as the
    /// connection check during onboarding.
    pub fn list_addressbooks(
        &mut self,
        base_url: &str,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, String> {
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
    ) -> Result<Vec<Card>, String> {
        match Backend::of(base_url) {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.list_jmap_cards(&session_url, credentials)
            }
            Backend::Google => self.list_google_cards(credentials.password),
            backend => Err(format!(
                "Cards of a {} account are listed per collection",
                backend.name(),
            )),
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
    ) -> Result<Vec<Card>, String> {
        match Backend::of(base_url) {
            Backend::Carddav => self.list_carddav_cards(&parse_url(addressbook_url)?, credentials),
            Backend::Graph => self.list_graph_cards(
                credentials.password,
                account::graph_folder(base_url, addressbook_url),
            ),
            backend => Err(format!(
                "Cards of a {} account are listed account-wide",
                backend.name(),
            )),
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
    ) -> Result<CardDelta, String> {
        let round = self.sync_cards_round(base_url, addressbook_url, credentials, cursor);

        let delta = match round {
            Ok(delta) => delta,
            Err(_) if cursor.is_none() && Backend::of(base_url) == Backend::Carddav => {
                // No sync-collection support on an initial CardDAV
                // sync: fall back to the plain enumeration (a genuine
                // outage fails there too, the same offline fallback).
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

        // The server no longer accepts the cursor: re-run an initial
        // round. A still-rejected initial round must never read as an
        // empty collection (that would look remote-deleted).
        match self.sync_cards_round(base_url, addressbook_url, credentials, None)? {
            Some(mut delta) => {
                delta.complete = true;
                Ok(delta)
            }
            None => Err(format!("Initial sync round rejected for {addressbook_url}")),
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
    ) -> Result<Option<CardDelta>, String> {
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
    ) -> Result<Card, String> {
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
    ) -> Result<Card, String> {
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
    ) -> Result<Card, String> {
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
    ) -> Result<(), String> {
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
    ) -> Result<(), String> {
        match Backend::of(base_url) {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(base_url)?;
                self.update_jmap_card_books(&session_url, credentials, id, add, remove)
            }
            Backend::Google => self.update_google_card_books(credentials.password, id, add, remove),
            backend => Err(format!(
                "Memberships of a {} card are fixed to its collection",
                backend.name(),
            )),
        }
    }

    // ---- CardDAV operations -------------------------------------------------

    /// Walks current-user-principal -> addressbook-home-set -> list,
    /// returning the account's addressbooks. Doubles as the connection
    /// check during onboarding: any failure (TLS, auth, discovery walk)
    /// surfaces here.
    pub fn list_carddav_addressbooks(
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
    pub fn list_carddav_cards(
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
    pub fn create_carddav_card(
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
    pub fn read_carddav_card(
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
    pub fn delete_carddav_card(
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

    /// Enumerates the card spine (resource name plus ETag, no body) of
    /// the addressbook collection at `url`.
    pub fn enum_cards(
        &mut self,
        url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<CardRef>, String> {
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
    ) -> Result<Option<SyncDelta>, String> {
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
    ) -> Result<Vec<Card>, String> {
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
    ) -> Result<Option<SyncDelta>, String> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(delta)) => return Ok(Some(delta)),
                WebdavCoroutineState::Complete(Err(SyncCollectionError::InvalidSyncToken)) => {
                    return Ok(None);
                }
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
    ) -> Result<Card, String> {
        let contact = msgraph::to_new_contact(vcard)?;
        let auth = HttpAuthBearer::new(token);
        let folder = (!folder.is_empty()).then_some(folder);

        let coroutine = MsgraphContactCreate::new(&auth, "me", folder, &contact)
            .map_err(|err| err.to_string())?;
        let mut created = self.run_msgraph(coroutine)?;

        // NOTE: create and update responses cannot $expand, so the
        // stash just sent is re-attached before projecting the
        // returned card.
        created.single_value_extended_properties =
            msgraph::to_contact(vcard)?.single_value_extended_properties;
        Ok(graph_card(created))
    }

    /// Reads the Graph contact `id`, projected onto a vCard document.
    pub fn read_graph_card(&mut self, token: &str, id: &str) -> Result<Card, String> {
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
    ) -> Result<Card, String> {
        let contact = match base_vcard {
            Some(base) => msgraph::to_contact_delta(vcard, base)?,
            None => msgraph::to_new_contact(vcard)?,
        };
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphContactUpdate::new(&auth, "me", id, &contact).map_err(|err| err.to_string())?;
        let mut updated = self.run_msgraph(coroutine)?;

        // NOTE: create and update responses cannot $expand, so the
        // card's full stash (unchanged deltas skip it in the body) is
        // re-attached before projecting the returned card.
        updated.single_value_extended_properties =
            msgraph::to_contact(vcard)?.single_value_extended_properties;
        Ok(graph_card(updated))
    }

    /// Deletes the Graph contact `id`.
    pub fn delete_graph_card(&mut self, token: &str, id: &str) -> Result<(), String> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphContactDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;

        Ok(())
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
    ) -> Result<Option<CardDelta>, String> {
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

    /// Lists the account's ContactCards across every AddressBook, each
    /// converted to a vCard document; the ContactCard id is the
    /// addressing key, a JSON hash the ETag, and the addressBookIds
    /// the card's books (natively m:n).
    pub fn list_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, String> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardQueryOptions::default();
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
            books: vec![book_id.to_string()],
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
    ) -> Result<Option<CardDelta>, String> {
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

        // A card created and destroyed within the window rides in
        // both lists; the destroy wins and the get skips it.
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
            books: Vec::new(),
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
    ) -> Result<(), String> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mut patch = BTreeMap::new();
        for book in add {
            patch.insert(
                format!("addressBookIds/{book}"),
                serde_json::Value::Bool(true),
            );
        }
        for book in remove {
            patch.insert(format!("addressBookIds/{book}"), serde_json::Value::Null);
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
            return Err(format!("JMAP membership update rejected: {err:?}"));
        }

        Ok(())
    }

    // ---- Google People operations -------------------------------------------

    /// Lists the Google account's contact groups as addressbooks: the
    /// myContacts system group first (as Contacts, the group every
    /// contact belongs to), then the user's own groups. Memberships
    /// are m:n labels, so one card can appear under several books.
    /// Doubles as the connection check during onboarding. Collection
    /// URLs are left empty: the caller composes them.
    pub fn list_google_addressbooks(&mut self, token: &str) -> Result<Vec<Addressbook>, String> {
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
                // container (starred, blocked and friends-style
                // legacy groups are not addressbooks).
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
    pub fn list_google_cards(&mut self, token: &str) -> Result<Vec<Card>, String> {
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
    ) -> Result<Option<CardDelta>, String> {
        let auth = HttpAuthBearer::new(token);

        // The person metadata carries the deleted marker of sync
        // responses; a sync token binds to its field mask, so every
        // round asks the same one.
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
                Err(err) if err.contains("EXPIRED_SYNC_TOKEN") => return Ok(None),
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
    pub fn create_google_card(&mut self, token: &str, vcard: &str) -> Result<Card, String> {
        let person = google::to_person(vcard)?;
        let auth = HttpAuthBearer::new(token);

        let coroutine = PeopleContactCreate::new(&auth, &person, google::READ_FIELDS, &[])
            .map_err(|err| err.to_string())?;
        Ok(google_card(self.run_google(coroutine)?))
    }

    /// Reads the People contact `id`, projected onto a vCard document.
    pub fn read_google_card(&mut self, token: &str, id: &str) -> Result<Card, String> {
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
    ) -> Result<Card, String> {
        let auth = HttpAuthBearer::new(token);
        let resource_name = format!("people/{id}");

        let mut person = google::to_person(vcard)?;
        person.resource_name = resource_name.clone();

        let fields = match base_vcard {
            Some(base) => google::changed_fields(&person, &google::to_person(base)?),
            None => google::MANAGED_FIELDS.to_vec(),
        };
        if fields.is_empty() {
            // NOTE: nothing differs from the base, no request to send.
            return Ok(Card {
                id: id.to_string(),
                uri: id.to_string(),
                etag: etag.map(str::to_string),
                vcard: vcard.to_string(),
                books: Vec::new(),
            });
        }

        // NOTE: a masked update replaces the whole clientData list and
        // other clients may own entries there, so a stash write merges
        // the server's foreign entries first (the etag guard turns a
        // lost-update race into a clean rejection). The same fetch
        // serves as etag source when the caller does not know it.
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
        Ok(google_card(self.run_google(coroutine)?))
    }

    /// Deletes the People contact `id`.
    pub fn delete_google_card(&mut self, token: &str, id: &str) -> Result<(), String> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = PeopleContactDelete::new(&auth, &format!("people/{id}"))
            .map_err(|err| err.to_string())?;
        self.run_google(coroutine)?;

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
    ) -> Result<(), String> {
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
                ));
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
                return Err("Google refused to remove the contact from its last group".to_string());
            }
            if !out.not_found_resource_names.is_empty() {
                return Err(format!(
                    "Google group member removal rejected: {:?} not found",
                    out.not_found_resource_names
                ));
            }
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

    /// Drives one discovery mechanism coroutine, converting transport
    /// failures on one endpoint into an EOF signal (an empty resume
    /// slice) so the coroutine can run its own fallbacks (the JMAP
    /// resolve falls back to the domain when the resolver is
    /// unreachable) and errors out on its own terms.
    fn run_mechanism<C, T, E>(&mut self, mut coroutine: C) -> Result<T, String>
    where
        C: DiscoveryCoroutine<Yield = DiscoveryYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(output)) => return Ok(output),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsRead { url }) => {
                    arg = Some(self.read(url.as_str()).unwrap_or_default());
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

    /// Runs a Google People coroutine to completion, routing every
    /// yield to the transport stream opened on the People API origin.
    fn run_google<C, T>(&mut self, mut coroutine: C) -> Result<T, String>
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
                PeopleCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
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

    /// Drives one JMAP `ContactCard/changes` round, surfacing an
    /// uncomputable state as [`None`] instead of an error (the generic
    /// [`Self::run_jmap`] erases the error variant the fallback needs).
    fn run_jmap_changes(
        &mut self,
        api_url: &Url,
        mut coroutine: JmapContactCardChanges,
    ) -> Result<Option<JmapChangesOutput>, String> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(out)) => return Ok(Some(out)),
                JmapCoroutineState::Complete(Err(JmapContactCardChangesError::Changes(
                    JmapChangesError::Method(JmapMethodError::CannotCalculateChanges { .. }),
                ))) => return Ok(None),
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

    /// Drives one Graph contacts delta request, surfacing an expired
    /// delta link (HTTP 410) as [`None`] instead of an error (the
    /// generic [`Self::run_msgraph`] erases the status the fallback
    /// needs).
    fn run_msgraph_delta<C>(
        &mut self,
        mut coroutine: C,
    ) -> Result<Option<MsgraphContactsDeltaResponse>, String>
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

/// Parses an absolute URL off the JNI wire.
fn parse_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}"))
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
    oauth_request("POST", endpoint)
}

/// GET request skeleton against a metadata endpoint, Host header
/// included (the io-oauth coroutines add the Accept header).
fn oauth_get_request(endpoint: &Url) -> HttpRequest {
    oauth_request("GET", endpoint)
}

/// Request skeleton with the Host header the HTTP/1.1 serializer
/// needs (fastmail and others 400 a request without one).
fn oauth_request(method: &str, endpoint: &Url) -> HttpRequest {
    let host = endpoint.host_str().unwrap_or("");
    let port = endpoint.port_or_known_default().unwrap_or(443);

    HttpRequest {
        method: method.into(),
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
        books: Vec::new(),
    }
}

/// io-google-people person to the JNI-facing card shape: the projected
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

/// Parses an OData paging link served by Graph.
fn parse_graph_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid Graph page URL `{raw}`: {err}"))
}

/// Splits a trimmed search input into (email, domain), the domain
/// lowercased. A bare domain (no `@`) yields an empty email and the
/// whole input as domain: every mechanism the app drives is
/// domain-driven, so a domain searches just as well as an address.
fn search_domain(input: &str) -> Result<(String, String), String> {
    let input = input.trim();

    let (email, domain) = match input.split_once('@') {
        Some((_, domain)) => (input, domain),
        None => ("", input),
    };

    let domain = domain.trim_matches('.').to_ascii_lowercase();

    if domain.is_empty() {
        return Err(format!("Search input `{input}` has no domain"));
    }

    Ok((email.to_string(), domain))
}

/// The `$expand` clause fetching the bridge's stash extended property
/// along with the contact (Graph omits extended properties otherwise).
fn graph_expand() -> String {
    format!(
        "singleValueExtendedProperties($filter=id eq '{}')",
        msgraph::EXTENDED_PROP_ID
    )
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

/// Catches and clears any pending Java exception, surfacing its class
/// and message (a bare JNI error only says "Java exception was thrown").
pub(crate) fn clear_and_fail(env: &mut Env, op: &str, err: Error) -> String {
    match env.exception_catch() {
        Err(Error::CaughtJavaException { name, msg, .. }) => {
            format!("{op} failed: {name}: {msg}")
        }
        _ => format!("{op} failed: {err}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An email address searches by its domain part; a bare domain
    /// searches as itself with no email; an input without any domain
    /// is rejected.
    #[test]
    fn search_domain_accepts_emails_and_bare_domains() {
        let (email, domain) = search_domain("user@Example.COM.").unwrap();
        assert_eq!(email, "user@Example.COM.");
        assert_eq!(domain, "example.com");

        let (email, domain) = search_domain(" example.com ").unwrap();
        assert_eq!(email, "");
        assert_eq!(domain, "example.com");

        assert!(search_domain("").is_err());
        assert!(search_domain("user@").is_err());
    }
}
