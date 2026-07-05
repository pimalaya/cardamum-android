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
use std::{borrow::Cow, collections::BTreeSet};

use io_http::{
    rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic, rfc9110::request::HttpRequest,
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
use url::Url;

use crate::{
    oauth::parse_pkce_verifier,
    types::{Addressbook, Card, Credentials},
};

/// DNS-over-TCP resolver used for the RFC 6764 SRV/TXT lookups when the
/// device did not expose its own.
const DNS_RESOLVER: &str = "tcp://1.1.1.1:53";

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
    /// RFC 6764: SRV and TXT over the given `tcp://host:port` resolver
    /// (the device's own when the caller found one), then `.well-known`.
    /// When the resolver is unreachable (mobile networks routinely block
    /// outbound DNS) it retries with the `.well-known` probe alone,
    /// whose HTTPS socket resolves the domain through the OS.
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

    /// Searches every pimconf mechanism for CardDAV service configs:
    /// fixed provider rules (Google/Microsoft, matched by domain then
    /// by MX records), PACC, then the RFC 6764 resolve. Each config
    /// carries its endpoint and authentication methods (password,
    /// OAuth 2.0 grants). With `first`, stops at the first mechanism
    /// yielding a config.
    pub fn search(
        &mut self,
        email: &str,
        resolver: Option<&str>,
        first: bool,
    ) -> Result<Vec<ServiceConfig>, String> {
        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        let resolver = Url::parse(resolver)
            .map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}"))?;

        let services = BTreeSet::from([Service::Carddav]);

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
    /// verifier of the authorization request.
    pub fn oauth_request_access_token(
        &mut self,
        token_endpoint: &Url,
        client_id: &str,
        code: &str,
        redirect_uri: &str,
        pkce_verifier: &str,
    ) -> Result<Oauth20IssueAccessTokenSuccessParams, String> {
        let verifier = parse_pkce_verifier(pkce_verifier)?;
        let params = Oauth20AccessTokenRequestParams {
            code: code.into(),
            redirect_uri: Some(redirect_uri.into()),
            client_id: client_id.into(),
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
    /// the original grant (empty keeps the server default).
    pub fn oauth_refresh_access_token(
        &mut self,
        token_endpoint: &Url,
        client_id: &str,
        refresh_token: &str,
        scope: &str,
    ) -> Result<Oauth20IssueAccessTokenSuccessParams, String> {
        let mut params = Oauth20RefreshAccessTokenParams::new(client_id, refresh_token);
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
