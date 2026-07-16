//! Discovery and OAuth operations: RFC 6764 CardDAV discovery, provider
//! and protocol search, the OAuth 2.0 token exchanges the onboarding
//! flow runs, and the CardDAV discovery walk (the PROPFIND probes that
//! resolve the principal and its addressbook home set).

use core::error::Error as StdError;

use std::borrow::Cow;

use io_http::rfc9110::request::HttpRequest;
use io_oauth::{
    rfc6749::{
        access_token_request::{
            Oauth20AccessTokenRequest, Oauth20AccessTokenRequestParams,
            Oauth20AccessTokenRequestResult,
        },
        issue_access_token::{Oauth20AccessTokenErrorParams, Oauth20AccessTokenSuccessParams},
        refresh_access_token::{
            Oauth20AccessTokenRefresh, Oauth20AccessTokenRefreshParams,
            Oauth20AccessTokenRefreshResult,
        },
    },
    rfc7591::register::{
        Oauth20ClientInformation, Oauth20ClientRegister, Oauth20ClientRegisterParams,
        Oauth20ClientRegisterResult,
    },
};
use io_pim_discovery::{
    autoconfig::mx::DiscoveryDnsMx,
    compose::{
        config::{DiscoveryService, DiscoveryServiceConfig},
        providers::DiscoveryKnownProvider,
    },
    coroutine::{DiscoveryCoroutine, DiscoveryCoroutineState, DiscoveryYield},
    pacc::discover::DiscoveryPacc,
    rfc6764::{
        resolve::DiscoveryDavResolve, service::DiscoveryDavService, well_known::DiscoveryWellKnown,
    },
    rfc8414::{DiscoveryOauthServerMetadata, DiscoveryOauthServerResolve},
    rfc8620::resolve::DiscoveryJmapResolve,
    rfc9110::DiscoveryProbeAuth,
};
use io_webdav::{
    rfc4918::WebdavAuth, rfc5397::current_user_principal::CurrentUserPrincipal,
    rfc6352::addressbook::home_set::AddressbookHomeSet,
};
use secrecy::SecretString;
use url::Url;

use crate::{
    client::{Client, USER_AGENT, convert::coroutine_error},
    oauth::parse_pkce_verifier,
    types::BridgeError,
};

/// RFC 8484 DNS-over-HTTPS resolver used for the discovery DNS lookups
/// (SRV, TXT, MX) when the caller passed none. DoH rides the same TLS
/// transport as every other request, so it works on mobile networks
/// that block outbound DNS over TCP.
const DNS_RESOLVER: &str = "https://cloudflare-dns.com/dns-query";

/// Discovery and OAuth operations: RFC 6764 CardDAV discovery, provider
/// and protocol search, and the OAuth 2.0 token exchanges the onboarding
/// flow runs.
impl<'a, 'local> Client<'a, 'local> {
    /// Resolves the email's domain to a CardDAV context root via
    /// RFC 6764: SRV and TXT over the given resolver (`tcp://host:port`,
    /// or an RFC 8484 `https://…/dns-query` one; DoH by default), then
    /// `.well-known`. When the resolver is unreachable it retries with
    /// the `.well-known` probe alone, whose HTTPS socket resolves the
    /// domain through the OS.
    pub fn discover(&mut self, email: &str, resolver: Option<&str>) -> Result<Url, BridgeError> {
        let domain = email.rsplit('@').next().unwrap_or(email).trim();

        if domain.is_empty() {
            return Err("Email address is missing a domain".into());
        }

        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        let resolver = Url::parse(resolver)
            .map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}"))?;

        let coroutine = DiscoveryDavResolve::new(domain, DiscoveryDavService::Carddav, resolver);
        let srv_err = match self.run_discovery(coroutine) {
            Ok(url) => return Ok(url),
            Err(err) => err,
        };

        let origin = format!("https://{domain}/");
        let origin =
            Url::parse(&origin).map_err(|err| format!("Invalid origin URL `{origin}`: {err}"))?;

        let coroutine = DiscoveryWellKnown::new(origin.clone(), DiscoveryDavService::Carddav);
        match self.run_discovery(coroutine) {
            // NOTE: RFC 6764 §5: no redirect means the origin itself is
            // the context root.
            Ok(redirect) => Ok(redirect.unwrap_or(origin)),
            Err(err) => Err(format!("{err} (SRV/TXT lookup failed first: {srv_err})").into()),
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
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (email, domain) = search_domain(email)?;

        let mx = DiscoveryDnsMx::new(&domain, self.search_resolver(resolver)?);
        let records = self.run_mechanism(mx)?;

        for record in records {
            let exchange = record.rdata.exchange.to_string();

            if let Some(provider) = DiscoveryKnownProvider::from_mx(&exchange) {
                let mut configs = provider.configs(&email);

                // NOTE: a bare-domain search has no email, so the
                // username hint is dropped rather than minted.
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
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let pacc = DiscoveryPacc::new(&domain, self.search_resolver(resolver)?)
            .map_err(|err| err.to_string())?;
        let config = self.run_mechanism(pacc)?;

        Ok(DiscoveryServiceConfig::from_pacc(&config))
    }

    /// Resolves the email domain's CardDAV context root (RFC 6764)
    /// into a service config.
    pub fn search_carddav(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let resolve = DiscoveryDavResolve::new(
            &domain,
            DiscoveryDavService::Carddav,
            self.search_resolver(resolver)?,
        );
        let url = self.run_mechanism(resolve)?;

        Ok(vec![DiscoveryServiceConfig::from_dav(
            DiscoveryService::Carddav,
            url,
        )])
    }

    /// Resolves the email domain's JMAP session URL (RFC 8620) into a
    /// service config.
    pub fn search_jmap(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let resolve = DiscoveryJmapResolve::new(&domain, self.search_resolver(resolver)?);
        let session = self.run_mechanism(resolve)?;

        Ok(vec![DiscoveryServiceConfig::from_jmap(
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
    pub fn search_probe(
        &mut self,
        mut config: DiscoveryServiceConfig,
    ) -> Result<DiscoveryServiceConfig, BridgeError> {
        for url in config.probe_urls() {
            match self.run_mechanism(DiscoveryProbeAuth::new(url)) {
                Ok(schemes) if !schemes.is_empty() => {
                    config.refine_auth(&schemes);
                    break;
                }
                // NOTE: a probe that fails or learns nothing is
                // best-effort; the next URL gets its turn.
                Ok(_) | Err(_) => {}
            }
        }

        Ok(config)
    }

    /// The DNS resolver URL for the search mechanisms, DoH by default.
    fn search_resolver(&self, resolver: Option<&str>) -> Result<Url, BridgeError> {
        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        Url::parse(resolver)
            .map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}").into())
    }

    /// Fetches an authorization server's RFC 8414 metadata from its
    /// issuer, trying the `oauth-authorization-server` well-known
    /// first and falling back to the OpenID Connect Discovery
    /// document (the mailmaint OAuth draft requires servers to serve
    /// at least one).
    pub fn oauth_server_metadata(
        &mut self,
        issuer: &Url,
    ) -> Result<DiscoveryOauthServerMetadata, BridgeError> {
        let primary = DiscoveryOauthServerMetadata::well_known_url(issuer);
        match self.run_discovery(DiscoveryOauthServerResolve::new(primary)) {
            Ok(metadata) => Ok(metadata),
            Err(primary_err) => {
                let fallback = DiscoveryOauthServerMetadata::openid_well_known_url(issuer);
                self.run_discovery(DiscoveryOauthServerResolve::new(fallback))
                    .map_err(|fallback_err| {
                        format!("{primary_err} (OpenID fallback also failed: {fallback_err})")
                            .into()
                    })
            }
        }
    }

    /// Registers a public client at an RFC 7591 registration endpoint
    /// (no secret, `token_endpoint_auth_method: none`), returning the
    /// server-issued client information.
    pub fn oauth_register_client(
        &mut self,
        registration_endpoint: &Url,
        params: &Oauth20ClientRegisterParams,
    ) -> Result<Oauth20ClientInformation, BridgeError> {
        let request = oauth_post_request(registration_endpoint);
        let mut coroutine =
            Oauth20ClientRegister::new(request, params).map_err(|err| err.to_string())?;
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20ClientRegisterResult::Ok(Ok(client)) => return Ok(client),
                Oauth20ClientRegisterResult::Ok(Err(err)) => {
                    let detail = err
                        .error_description
                        .unwrap_or_else(|| format!("{:?}", err.error));
                    return Err(format!("Client registration rejected: {detail}").into());
                }
                Oauth20ClientRegisterResult::Err(err) => return Err(err.to_string().into()),
                Oauth20ClientRegisterResult::WantsRead => {
                    arg = Some(self.read(registration_endpoint.as_str())?);
                }
                Oauth20ClientRegisterResult::WantsWrite(bytes) => {
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
    ) -> Result<Oauth20AccessTokenSuccessParams, BridgeError> {
        let verifier = parse_pkce_verifier(pkce_verifier)?;
        let params = Oauth20AccessTokenRequestParams {
            code: code.into(),
            redirect_uri: Some(redirect_uri.into()),
            client_id: client_id.into(),
            client_secret: client_secret.map(SecretString::from),
            pkce_code_verifier: Some(Cow::Owned(verifier)),
        };

        let mut coroutine =
            Oauth20AccessTokenRequest::new(oauth_post_request(token_endpoint), params);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20AccessTokenRequestResult::Ok(Ok(success)) => return Ok(success),
                Oauth20AccessTokenRequestResult::Ok(Err(err)) => {
                    return Err(oauth_error(err).into());
                }
                Oauth20AccessTokenRequestResult::Err(err) => return Err(err.to_string().into()),
                Oauth20AccessTokenRequestResult::WantsRead => {
                    arg = Some(self.read(token_endpoint.as_str())?);
                }
                Oauth20AccessTokenRequestResult::WantsWrite(bytes) => {
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
    ) -> Result<Oauth20AccessTokenSuccessParams, BridgeError> {
        let mut params = Oauth20AccessTokenRefreshParams::new(client_id, refresh_token);
        params.client_secret = client_secret.map(SecretString::from);
        params.scopes = scope.split_whitespace().map(Cow::Borrowed).collect();

        let mut coroutine =
            Oauth20AccessTokenRefresh::new(oauth_post_request(token_endpoint), params);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20AccessTokenRefreshResult::Ok(Ok(success)) => return Ok(success),
                Oauth20AccessTokenRefreshResult::Ok(Err(err)) => {
                    return Err(oauth_error(err).into());
                }
                Oauth20AccessTokenRefreshResult::Err(err) => return Err(err.to_string().into()),
                Oauth20AccessTokenRefreshResult::WantsRead => {
                    arg = Some(self.read(token_endpoint.as_str())?);
                }
                Oauth20AccessTokenRefreshResult::WantsWrite(bytes) => {
                    self.write(token_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// CardDAV discovery walk steps: the PROPFIND probes that resolve the
/// principal and its addressbook home set.
impl<'a, 'local> Client<'a, 'local> {
    /// PROPFIND the context root for `DAV:current-user-principal`.
    pub(crate) fn principal(
        &mut self,
        base_url: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        self.run_redirect(base_url, |url| {
            CurrentUserPrincipal::new(url, auth, USER_AGENT)
        })
    }

    /// PROPFIND the principal for `CARDDAV:addressbook-home-set`.
    pub(crate) fn addressbook_home_set(
        &mut self,
        principal: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        self.run_redirect(principal, |url| {
            AddressbookHomeSet::new(url, auth, USER_AGENT, url.path())
        })
    }
}

/// Discovery coroutine runners: the resume loops that pump each
/// io-pim-discovery coroutine, routing every read and write yield to the
/// Java transport stream opened for the yielded URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Drives a discovery coroutine to completion, routing each yield
    /// to the stream the Java transport opens for the yielded URL.
    pub(crate) fn run_discovery<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: DiscoveryCoroutine<Yield = DiscoveryYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(url)) => return Ok(url),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
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
    fn run_mechanism<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: DiscoveryCoroutine<Yield = DiscoveryYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(output)) => return Ok(output),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
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
}

/// POST request skeleton against the token or registration endpoint
/// (the io-oauth coroutines add the content headers and body), with
/// the explicit `host:port` Host header the fastmail flow was
/// verified with.
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
fn oauth_error(err: Oauth20AccessTokenErrorParams) -> String {
    match err.error_description {
        Some(description) => format!("OAuth error {:?}: {description}", err.error),
        None => format!("OAuth error {:?}", err.error),
    }
}

/// Splits a trimmed search input into (email, domain), the domain
/// lowercased. A bare domain (no `@`) yields an empty email and the
/// whole input as domain: every mechanism the app drives is
/// domain-driven, so a domain searches just as well as an address.
pub(crate) fn search_domain(input: &str) -> Result<(String, String), BridgeError> {
    let input = input.trim();

    let (email, domain) = match input.split_once('@') {
        Some((_, domain)) => (input, domain),
        None => ("", input),
    };

    let domain = domain.trim_matches('.').to_ascii_lowercase();

    if domain.is_empty() {
        return Err(format!("Search input `{input}` has no domain").into());
    }

    Ok((email.to_string(), domain))
}
