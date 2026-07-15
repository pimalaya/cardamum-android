//! JNI entry points called from the Cardamum `:client` module.
//!
//! Every method captures the FFI [`EnvUnowned`], upgrades it to a usable
//! [`Env`] inside [`EnvUnowned::with_env`] (which also guards against
//! unwinding across the JNI boundary), and resolves the outcome with
//! [`LogErrorAndDefault`] so an unexpected JNI failure logs and returns a
//! null string rather than throwing. Every reply is JSON; failures are
//! `{"error": ".."}`.

use std::collections::BTreeSet;

use io_oauth::rfc7591::register::{Oauth20ClientInformation, Oauth20RegisterClientParams};
use io_pim_discovery::compose::{
    collect::ConfigCollector,
    types::{Service, ServiceConfig},
};
use jni::{
    Env, EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
    sys::{jboolean, jint},
};
use url::Url;

use crate::{
    account::{self, Backend},
    client::Client,
    oauth::{authorize_url, contacts_scope, session_params, validate_redirect},
    offline,
    project::{
        apply, card_prop_labels, card_props, card_set_prop, card_set_prop_parts, card_source,
        card_type_order, duplicate_group, find_duplicates, form_date, form_entry, form_view,
        group_contacts, index, merge_cards, merge_conflict, merge_conflict_form, project, set_uid,
    },
    store,
    types::{BridgeError, CardDelta, Credentials, PushChange},
};

/// `Native.discover`: resolves the email's domain to a CardDAV context
/// root via RFC 6764, using the given DNS resolver (`tcp://host:port`
/// or an RFC 8484 `https://…/dns-query` URL; empty or null falls back
/// to a public DNS-over-HTTPS one). Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_discover<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let email = read_string(env, &email);
        let resolver = read_string(env, &resolver);
        let resolver = (!resolver.is_empty()).then_some(resolver.as_str());

        let json = match Client::new(env, &transport).discover(&email, resolver) {
            Ok(url) => serde_json::json!({ "url": url.as_str() }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchProvider`: matches the email's MX records against
/// the fixed provider rules. Returns a JSON array of service configs,
/// empty when no rule matched.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchProvider<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(
            env,
            &transport,
            &email,
            &resolver,
            SearchMechanism::Provider,
        );
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchPacc`: discovers the email domain's PACC document.
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchPacc<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Pacc);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchCarddav`: resolves the email domain's CardDAV context
/// root (RFC 6764). Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchCarddav<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json =
            search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Carddav);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchJmap`: resolves the email domain's JMAP session URL
/// (RFC 8620). Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchJmap<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Jmap);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchMerge`: pure reduction of per-mechanism config lists
/// (a JSON array of arrays, in mechanism-priority order) into one
/// deduplicated list, restricted to the services the app drives
/// (CardDAV, JMAP). Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchMerge<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    lists: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match search_merge(&read_string(env, &lists)) {
            Ok(json) => json,
            Err(err) => error_json(err),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchProbe`: probes one config's endpoints for their
/// advertised authentication schemes (unauthenticated 401) and
/// refines its password and bearer methods. Returns the (possibly
/// refined) config as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchProbe<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    config: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = read_string(env, &config);

        let json = match serde_json::from_str(&raw) {
            Err(err) => error_json(format!("Invalid service config: {err}")),
            Ok(config) => match Client::new(env, &transport).search_probe(config) {
                Ok(config) => {
                    serde_json::to_string(&config).unwrap_or_else(|err| error_json(err.to_string()))
                }
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthAuthorizeUrl`: builds the RFC 6749 authorization URL
/// with PKCE (S256) and CSRF state; pure computation, no transport.
/// `extras` is a JSON object of provider-specific query parameters
/// (empty or null for none). Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthAuthorizeUrl<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    authorization_endpoint: JString<'local>,
    client_id: JString<'local>,
    redirect_uri: JString<'local>,
    scope: JString<'local>,
    state: JString<'local>,
    pkce_verifier: JString<'local>,
    extras: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let authorization_endpoint = read_string(env, &authorization_endpoint);
        let client_id = read_string(env, &client_id);
        let redirect_uri = read_string(env, &redirect_uri);
        let scope = read_string(env, &scope);
        let state = read_string(env, &state);
        let pkce_verifier = read_string(env, &pkce_verifier);
        let extras = read_string(env, &extras);

        let json = match authorize_url(
            &authorization_endpoint,
            &client_id,
            &redirect_uri,
            &scope,
            &state,
            &pkce_verifier,
            &extras,
        ) {
            Ok(url) => serde_json::json!({ "url": url }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthValidateRedirect`: validates the authorization
/// redirect against the expected CSRF state and extracts the
/// authorization code; pure computation, no transport. Returns
/// `{"code": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthValidateRedirect<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    redirect_url: JString<'local>,
    state: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let redirect_url = read_string(env, &redirect_url);
        let state = read_string(env, &state);

        let json = match validate_redirect(&redirect_url, &state) {
            Ok(code) => serde_json::json!({ "code": code }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthSessionParams`: generates one authorization session's
/// CSRF state and PKCE verifier; pure computation, no transport.
/// Returns `{"state": "..", "verifier": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthSessionParams<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let (state, verifier) = session_params();
        let json = serde_json::json!({ "state": state, "verifier": verifier }).to_string();

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthContactsScope`: the scope a contacts client requests
/// of an authorization server, negotiated against its space-separated
/// advertised scopes (empty for none); pure computation, no transport.
/// Returns `{"scope": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthContactsScope<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    scopes_supported: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let scopes_supported = read_string(env, &scopes_supported);
        let scope = contacts_scope(&scopes_supported);
        let json = serde_json::json!({ "scope": scope }).to_string();

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthRequestAccessToken`: exchanges an authorization code
/// for tokens against the token endpoint, with the PKCE verifier of
/// the authorization request and the client secret when the
/// registration issued one (empty means none). Returns the RFC 6749
/// §5.1 success params as JSON (`access_token`, `token_type`,
/// `expires_in`, `refresh_token`, `scope`, `issued_at`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthRequestAccessToken<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token_endpoint: JString<'local>,
    client_id: JString<'local>,
    client_secret: JString<'local>,
    code: JString<'local>,
    redirect_uri: JString<'local>,
    pkce_verifier: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token_endpoint = read_string(env, &token_endpoint);
        let client_id = read_string(env, &client_id);
        let client_secret = read_string(env, &client_secret);
        let secret = (!client_secret.is_empty()).then_some(client_secret.as_str());
        let code = read_string(env, &code);
        let redirect_uri = read_string(env, &redirect_uri);
        let pkce_verifier = read_string(env, &pkce_verifier);

        let json = match parse_url(&token_endpoint) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(env, &transport).oauth_request_access_token(
                &url,
                &client_id,
                secret,
                &code,
                &redirect_uri,
                &pkce_verifier,
            ) {
                Ok(tokens) => {
                    serde_json::to_string(&tokens).unwrap_or_else(|err| error_json(err.to_string()))
                }
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthServerMetadata`: fetches an authorization server's
/// RFC 8414 metadata from its issuer (the `oauth-authorization-server`
/// well-known, falling back to the OpenID Connect Discovery document).
/// Returns the metadata JSON (issuer, endpoints, `registration_endpoint`
/// when the server supports RFC 7591, grants, scopes).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthServerMetadata<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    issuer: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let issuer = read_string(env, &issuer);

        let json = match parse_url(&issuer) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(env, &transport).oauth_server_metadata(&url) {
                Ok(metadata) => serde_json::to_string(&metadata)
                    .unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthRegisterClient`: registers a public client at an
/// RFC 7591 registration endpoint (`token_endpoint_auth_method: none`,
/// the given redirect URI, code + refresh grants, client name and
/// scope). Returns `{"client_id": "..", "client_secret": ".." | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthRegisterClient<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    registration_endpoint: JString<'local>,
    redirect_uri: JString<'local>,
    client_name: JString<'local>,
    scope: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let registration_endpoint = read_string(env, &registration_endpoint);
        let redirect_uri = read_string(env, &redirect_uri);
        let client_name = read_string(env, &client_name);
        let scope = read_string(env, &scope);

        let params = oauth_register_params(&redirect_uri, &client_name, &scope);

        let json = match parse_url(&registration_endpoint) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(env, &transport).oauth_register_client(&url, &params) {
                Ok(client) => client_information_json(&client),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.oauthRefreshAccessToken`: refreshes tokens against the
/// token endpoint; `scope` is the space-separated scope list of the
/// original grant (empty keeps the server default) and the client
/// secret rides along when the registration issued one (empty means
/// none). Returns the same JSON shape as `oauthRequestAccessToken`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_oauthRefreshAccessToken<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token_endpoint: JString<'local>,
    client_id: JString<'local>,
    client_secret: JString<'local>,
    refresh_token: JString<'local>,
    scope: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token_endpoint = read_string(env, &token_endpoint);
        let client_id = read_string(env, &client_id);
        let client_secret = read_string(env, &client_secret);
        let secret = (!client_secret.is_empty()).then_some(client_secret.as_str());
        let refresh_token = read_string(env, &refresh_token);
        let scope = read_string(env, &scope);

        let json = match parse_url(&token_endpoint) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(env, &transport).oauth_refresh_access_token(
                &url,
                &client_id,
                secret,
                &refresh_token,
                &scope,
            ) {
                Ok(tokens) => {
                    serde_json::to_string(&tokens).unwrap_or_else(|err| error_json(err.to_string()))
                }
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.accountInfo`: the backend behind an account base URL
/// (`carddav`, `graph`, `jmap`, `google`, or `local` for the built-in
/// on-device account) and whether its cards are account-level resources
/// with m:n addressbook memberships; pure computation, no transport.
/// Returns `{"backend": "..", "accountLevel": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_accountInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base_url: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);

        // The local account has no transport: it is not a Backend, and
        // reporting it as one keeps its cards non-account-level (one
        // book, per-collection keys) and out of every backend match.
        let (backend, account_level) = if base_url.starts_with(account::LOCAL_PREFIX) {
            ("local", false)
        } else {
            let backend = Backend::of(&base_url);
            (backend.name(), backend.account_level())
        };

        let json = serde_json::json!({
            "backend": backend,
            "accountLevel": account_level,
        })
        .to_string();

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.accountBase`: builds an account base URL of the given kind
/// (`googleCarddav`, `google`, `msgraph` from an email, `jmap` from an
/// HTTPS session URL or bare host); pure computation, no transport.
/// Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_accountBase<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    value: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let value = read_string(env, &value);

        let json = match account::base_url(&kind, &value) {
            Ok(url) => serde_json::json!({ "url": url }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listAddressbooks`: lists the account's addressbooks, the
/// backend dispatched from the base URL. Returns a JSON array of
/// addressbooks carrying absolute collection URLs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listAddressbooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).list_addressbooks(&base_url, &credentials) {
            Ok(books) => {
                serde_json::to_string(&books).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listAccountCards`: lists every card of an account-level
/// backend (JMAP, Google) in one pass, each carrying its addressbook
/// memberships as book ids. Returns a JSON array of
/// `{id, uri, etag, vcard, books}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listAccountCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).list_account_cards(&base_url, &credentials) {
            Ok(cards) => {
                serde_json::to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listCards`: lists the cards of the addressbook collection,
/// the backend dispatched from the base URL (CardDAV, Graph). Returns
/// a JSON array of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).list_cards(
            &base_url,
            &addressbook_url,
            &credentials,
        ) {
            Ok(cards) => {
                serde_json::to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createCard`: creates the card in the addressbook
/// collection, the backend dispatched from the base URL. Returns the
/// created `{id, uri, etag, vcard}` (the server-assigned id on the
/// backends naming the resource themselves).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).create_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &id,
            &vcard,
        ) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readCard`: reads the card at the given resource name (as
/// the server returned it), the backend dispatched from the base URL.
/// Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_readCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uri: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uri = read_string(env, &uri);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).read_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &uri,
        ) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateCard`: updates the card at the given resource name
/// (as the server returned it; empty falls back to the id), the
/// backend dispatched from the base URL. The base vCard trims the
/// patching backends' updates to the fields the edit changed and the
/// ETag guards the guarding backends' writes (empty means unknown for
/// both). Returns the updated `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    uri: JString<'local>,
    vcard: JString<'local>,
    base_vcard: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let uri = read_string(env, &uri);
        let vcard = read_string(env, &vcard);
        let base_vcard = read_string(env, &base_vcard);
        let base = (!base_vcard.is_empty()).then_some(base_vcard.as_str());
        let etag = read_string(env, &etag);
        let if_match = (!etag.is_empty()).then_some(etag.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).update_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &id,
            &uri,
            &vcard,
            base,
            if_match,
        ) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteCard`: deletes the card at the given resource name
/// (as the server returned it; empty falls back to the id), the
/// backend dispatched from the base URL and the ETag guarding the
/// guarding backends' deletion (empty means unknown). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    uri: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let uri = read_string(env, &uri);
        let etag = read_string(env, &etag);
        let if_match = (!etag.is_empty()).then_some(etag.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).delete_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &id,
            &uri,
            if_match,
        ) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createCards`: creates a batch of cards (a JSON array of
/// vCard documents) in the addressbook collection, Google-only (the
/// one backend with a batch create verb). Returns a JSON array of the
/// created `{id, uri, etag, vcard}` in input order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    vcards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let vcards = read_string(env, &vcards);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_strings(&vcards) {
            Ok(vcards) => {
                match Client::new(env, &transport).create_cards(&base_url, &credentials, &vcards) {
                    Ok(cards) => serde_json::to_string(&cards)
                        .unwrap_or_else(|err| error_json(err.to_string())),
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteCards`: deletes a batch of cards (a JSON array of
/// card ids) from the addressbook collection, Google-only (the one
/// backend with a batch delete verb). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let ids = read_string(env, &ids);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_strings(&ids) {
            Ok(ids) => {
                match Client::new(env, &transport).delete_cards(&base_url, &credentials, &ids) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.pushCards`: pushes a round of changes (a JSON array of
/// `{ref, op, id?, vcard?, baseVcard?, add?, remove?}`) to the
/// addressbook collection as batch calls, JMAP (ContactCard/set) and
/// Graph ($batch) only. Returns a JSON array of `{ref, accepted, id?,
/// etag?, error?}`, one outcome per change.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_pushCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    changes: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let changes = read_string(env, &changes);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match serde_json::from_str::<Vec<PushChange>>(&changes) {
            Ok(changes) => {
                match Client::new(env, &transport).push_cards(
                    &base_url,
                    &addressbook_url,
                    &credentials,
                    &changes,
                ) {
                    Ok(outcomes) => serde_json::to_string(&outcomes)
                        .unwrap_or_else(|err| error_json(err.to_string())),
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(format!("Unreadable push changes: {err}")),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.enumCards`: enumerates the card spine (resource name plus
/// ETag, no body) of the addressbook collection. Returns a JSON array
/// of `{id, uri, etag}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_enumCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&addressbook_url) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(env, &transport).enum_cards(&url, &credentials) {
                Ok(refs) => {
                    let refs: Vec<serde_json::Value> = refs
                        .into_iter()
                        .map(|entry| {
                            serde_json::json!({
                                "id": entry.id,
                                "uri": entry.uri,
                                "etag": entry.etag,
                            })
                        })
                        .collect();
                    serde_json::Value::Array(refs).to_string()
                }
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.syncCards`: lists the collection's changes since the given
/// cursor (empty runs the initial round: the complete member set plus
/// the cursor to delta from next time), the backend dispatched from
/// the base URL. An expired cursor re-runs an initial round and an
/// initial CardDAV sync a server rejects falls back to the plain
/// enumeration, both internally. Returns `{"changed": [{id, uri,
/// etag, vcard?, books?}], "vanished": [uri], "token": "..",
/// "complete": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_syncCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sync_token: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let sync_token = read_string(env, &sync_token);
        let sync_token = (!sync_token.is_empty()).then_some(sync_token.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).sync_cards(
            &base_url,
            &addressbook_url,
            &credentials,
            sync_token,
        ) {
            Ok(delta) => delta_json(delta),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.multigetCards`: batch-fetches the cards at the given
/// resource names (a JSON string array) inside the addressbook
/// collection via REPORT `addressbook-multiget`. Returns a JSON array
/// of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_multigetCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uris: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uris = read_string(env, &uris);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match (parse_url(&addressbook_url), parse_strings(&uris)) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(url), Ok(uris)) => {
                let uris: Vec<&str> = uris.iter().map(String::as_str).collect();
                match Client::new(env, &transport).multiget_cards(&url, &credentials, &uris) {
                    Ok(cards) => serde_json::to_string(&cards)
                        .unwrap_or_else(|err| error_json(err.to_string())),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateCardBooks`: adds and removes the card's addressbook
/// memberships on an account-level backend (JSON string arrays of book
/// ids), the backend dispatched from the base URL. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateCardBooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    add: JString<'local>,
    remove: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let add = read_string(env, &add);
        let remove = read_string(env, &remove);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match (parse_books(&add), parse_books(&remove)) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(add), Ok(remove)) => {
                match Client::new(env, &transport).update_card_books(
                    &base_url,
                    &credentials,
                    &id,
                    &add,
                    &remove,
                ) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineSync`: reconciles the collection with its remote
/// through the io-offline engine, servicing every engine yield via the
/// given `OfflineDriver`. With `full` the checkpoint is ignored and the
/// whole remote is enumerated. Returns the sync report
/// `{"pulled", "pushed", "conflicts", "rejected", "refreshed"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineSync<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    full: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);

        let json = match offline::sync(env, &driver, &collection, full) {
            Ok(report) => report.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineUpgrade`: raises the given handles (a JSON string
/// array) to the full detail tier through the io-offline engine,
/// servicing every engine yield via the given `OfflineDriver`. Returns
/// the upgrade report `{"upgraded", "fetched", "deduped"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineUpgrade<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    handles: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let handles = read_string(env, &handles);

        let json = match parse_strings(&handles) {
            Err(err) => error_json(err),
            Ok(handles) => match offline::upgrade(env, &driver, &collection, handles) {
                Ok(report) => report.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineMutate`: stages a local mutation (a JSON object,
/// e.g. `{"op": "edit", "handle", "hash", "size", "body", "meta"}`)
/// through the io-offline engine, servicing the storage yields via the
/// given `OfflineDriver`; the remote is never touched. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineMutate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    mutation: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let mutation = read_string(env, &mutation);

        let json = match offline::mutate(env, &driver, &collection, &mutation) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineRetryUnguarded`: whether a 412-rejected push may
/// retry unguarded, the last enumerate proving the handle unchanged
/// (the CardDAV If-Match quirk); pure computation, no transport. Takes
/// `{"listed": {handle: etag}?, "complete", "handle", "ifMatch"?}`,
/// returns `{"retry": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineRetryUnguarded<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => serde_json::json!({ "retry": store::retry_unguarded(&facts) }).to_string(),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineAccountSnapshot`: projects an account-wide delta
/// (JMAP, Google) onto one book's enumerate; pure computation, no
/// transport. Takes `{"bookId"?, "complete", "changed": [{handle,
/// books, known}], "vanished"}`, returns `{"members": [index],
/// "vanished": [handle]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineAccountSnapshot<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::account_snapshot(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePushPlan`: plans one push change (membership patch
/// vs create or delete, plus the Google post-create membership); pure
/// computation, no transport. Takes `{"op", "collection", "bookId"?,
/// "origin", "deleted"}`, returns `{"action", "postCreateBooks"?}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePushPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::push_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePlacement`: maps one card-plus-membership row to its
/// engine placement on the server axis; pure computation, no
/// transport. Takes the row facts (see the store module), returns
/// `{"placement": {..} | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePlacement<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => placement_json(store::placement(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePhonePlacement`: maps one card-plus-membership row
/// to its phone-axis placement; pure computation, no transport. Takes
/// the row facts (see the store module), returns
/// `{"placement": {..} | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePhonePlacement<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => placement_json(store::phone_placement(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineUpsertPlan`: plans one engine upsert onto the card
/// and membership rows; pure computation, no transport. Takes the
/// placement and row facts (see the store module), returns
/// `{"action", "row"?, "memberState"?}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineUpsertPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::upsert_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePhoneUpsertPlan`: plans one phone-axis upsert; pure
/// computation, no transport. Takes the placement and row facts (see
/// the store module), returns `{"action", "row"?, "axis"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePhoneUpsertPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::phone_upsert_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePhoneDropPlan`: plans a phone-collection drop
/// (membership removal vs card deletion); pure computation, no
/// transport. Takes `{"collection", "deleted", "otherMemberships"}`,
/// returns `{"action"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePhoneDropPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::phone_drop_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.indexCard`: indexes a vCard for the store (display name,
/// first email and phone, UID, normalized content hash); pure
/// computation, no transport. Returns
/// `{"name", "email", "phone", "uid", "hash"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_indexCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match index(&vcard) {
            Ok(index) => index.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeCards`: merges several vCards (a JSON string array)
/// into one union document with its field model and the per-field
/// alternatives for the merge form (docs/merged-view.md); pure
/// computation, no transport. Returns
/// `{"vcard", "model", "alternatives"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_mergeCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let cards = read_string(env, &cards);

        let json = match parse_strings(&cards) {
            Err(err) => error_json(err),
            Ok(cards) => match merge_cards(&cards) {
                Ok(merged) => merged.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeCardChanges`: three-way merges a conflicted push (the
/// staged local edit and the fetched remote card against their common
/// base; the local side wins same-field collisions); pure computation,
/// no transport. Returns `{"vcard", "conflicts"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_mergeCardChanges<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match merge_conflict(&base, &local, &remote) {
            Ok(merged) => merged.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeConflictForm`: builds the conflict form's inputs for a
/// both-sides-edited row (the newer side by REV wins collisions as the
/// pre-filled default, both candidates offered per field); pure
/// computation, no transport. Returns `{"vcard", "model", "alternatives",
/// "changed"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_mergeConflictForm<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match merge_conflict_form(&base, &local, &remote) {
            Ok(form) => form.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.setCardUid`: rewrites the card's UID (a plain copy is a new
/// identity); pure computation, no transport. Returns
/// `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_setCardUid<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    uid: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let uid = read_string(env, &uid);

        let json = match set_uid(&vcard, &uid) {
            Ok(fresh) => serde_json::json!({ "vcard": fresh }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.findDuplicates`: finds groups of likely-duplicate cards
/// (exact normalized email, phone or name matches) for the duplicate
/// remover; pure computation, no transport. Takes a JSON array of
/// `{"ref", "vcard"}` pairs, returns
/// `{"groups": [{"refs": [...], "reasons": [...]}]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_findDuplicates<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let cards = read_string(env, &cards);

        let json = match parse_ref_cards(&cards) {
            Err(err) => error_json(err),
            Ok(cards) => match find_duplicates(&cards) {
                Ok(found) => found.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardProps`: lists the card's raw property lines for the
/// advanced editor; pure computation, no transport. Returns
/// `{"props": ["VERSION:4.0", ...]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardProps<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match card_props(&vcard) {
            Ok(props) => serde_json::json!({ "props": props }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardSetProp`: rewrites one raw property line for the
/// advanced editor (a blank line removes, index -1 appends); pure
/// computation, no transport. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardSetProp<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    index: jint,
    line: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let line = read_string(env, &line);

        let json = match card_set_prop(&vcard, index as i64, &line) {
            Ok(fresh) => serde_json::json!({ "vcard": fresh }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardSetPropParts`: recomposes one property from its
/// structured parts (`{"name", "params": [{"name", "values"}],
/// "value"}`) and rewrites it (index -1 appends); pure computation, no
/// transport. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardSetPropParts<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    index: jint,
    prop: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let prop = read_string(env, &prop);

        let json = match serde_json::from_str(&prop) {
            Err(err) => error_json(format!("Invalid property: {err}")),
            Ok(prop) => match card_set_prop_parts(&vcard, index as i64, &prop) {
                Ok(fresh) => serde_json::json!({ "vcard": fresh }).to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardPropLabels`: the component labels of a structured
/// property name (N, ADR, GENDER; empty for plain values), shaping the
/// advanced editor's value form; pure computation, no transport.
/// Returns `{"labels": [...]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardPropLabels<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let name = read_string(env, &name);
        let json = serde_json::json!({ "labels": card_prop_labels(&name) }).to_string();
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardTypeOrder`: the ordered type-set vocabulary the edit
/// form's spinners address for the kind (`phone`, `email`, `address`,
/// `relation`, `gender`), each position's vCard TYPE set in the order
/// the Android string-arrays must mirror; pure computation, no
/// transport. Returns `{"order": [[..], ..]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardTypeOrder<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let json = serde_json::json!({ "order": card_type_order(&kind) }).to_string();
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardSource`: validates a hand-edited vCard source (it must
/// reparse) and returns it re-serialized; pure computation, no
/// transport. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardSource<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match card_source(&vcard) {
            Ok(fresh) => serde_json::json!({ "vcard": fresh }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.projectCard`: projects a vCard onto the neutral field model
/// the app maps to ContactsContract rows (docs/contacts-mapping.md).
/// Returns the model JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_projectCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match project(&vcard) {
            Ok(model) => model.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.applyCard`: patches the edited field model back onto the
/// vCard, preserving every unmanaged property (docs/contacts-mapping.md).
/// Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_applyCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    model: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let model = read_string(env, &model);

        let json = match serde_json::from_str(&model) {
            Err(err) => error_json(format!("Invalid field model: {err}")),
            Ok(model) => match apply(&vcard, &model) {
                Ok(patched) => serde_json::json!({ "vcard": patched }).to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.formView`: the edit form's view support computed from the
/// field model (summaries, type spinner positions, picker dates); pure
/// computation, no transport. Returns `{"name", "organization",
/// "gender"?, "birthday"?, "anniversary"?, "phones", "emails",
/// "relations", "addresses"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_formView<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    model: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let model = read_string(env, &model);

        let json = match serde_json::from_str(&model) {
            Err(err) => error_json(format!("Invalid field model: {err}")),
            Ok(model) => form_view(&model).to_string(),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.formEntry`: one typed entry saved from an edit dialog, its
/// TYPE set drawn from the spinner position (`phone`, `email`,
/// `relation` return the full entry, `address` the TYPE set alone,
/// `gender` the GENDER object, empty when unset); pure computation, no
/// transport.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_formEntry<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    index: jint,
    value: JString<'local>,
    pref: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let value = read_string(env, &value);

        let json = match form_entry(&kind, index as i64, &value, pref) {
            Ok(entry) => entry.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.formDate`: one picked date on the model wire (the vCard
/// `yyyy-mm-dd` form, 1-based month); pure computation, no transport.
/// Returns `{"value": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_formDate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    year: jint,
    month: jint,
    day: jint,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let value = form_date(year as i64, month as i64, day as i64);
        let json = serde_json::json!({ "value": value }).to_string();

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.groupContacts`: groups the replica pool into merged
/// contacts (docs/merged-view.md), the groups sorted by primary
/// display name; pure computation, no transport. Takes `{"replicas":
/// [{ref, uid, name, id}], "links": {member: cluster}, "detached":
/// [ref]}`, returns `{"groups": [{key, replicas: [index]}]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_groupContacts<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let input = read_string(env, &input);

        let json = match serde_json::from_str(&input) {
            Err(err) => error_json(format!("Invalid replica pool: {err}")),
            Ok(input) => match group_contacts(&input) {
                Ok(groups) => groups.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.duplicateGroup`: the duplicate review's group facts, the
/// dismissal key and the Link eligibility; pure computation, no
/// transport. Takes `[{ref, book}]`, returns `{"key", "linkable"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_duplicateGroup<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    members: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let members = read_string(env, &members);

        let json = match serde_json::from_str(&members) {
            Err(err) => error_json(format!("Invalid duplicate group: {err}")),
            Ok(members) => match duplicate_group(&members) {
                Ok(facts) => facts.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// One discovery mechanism the search verbs dispatch on.
enum SearchMechanism {
    Provider,
    Pacc,
    Carddav,
    Jmap,
}

/// Runs one discovery mechanism and serializes its service configs.
fn search_mechanism_json<'local>(
    env: &mut Env<'local>,
    transport: &JObject<'local>,
    email: &JString<'local>,
    resolver: &JString<'local>,
    mechanism: SearchMechanism,
) -> String {
    let email = read_string(env, email);
    let resolver = read_string(env, resolver);
    let resolver = (!resolver.is_empty()).then_some(resolver.as_str());

    let mut client = Client::new(env, transport);
    let configs = match mechanism {
        SearchMechanism::Provider => client.search_provider(&email, resolver),
        SearchMechanism::Pacc => client.search_pacc(&email, resolver),
        SearchMechanism::Carddav => client.search_carddav(&email, resolver),
        SearchMechanism::Jmap => client.search_jmap(&email, resolver),
    };

    match configs {
        Ok(configs) => {
            serde_json::to_string(&configs).unwrap_or_else(|err| error_json(err.to_string()))
        }
        Err(err) => error_json(err),
    }
}

/// Merges per-mechanism config lists (in priority order) through
/// io-pim-discovery's pure collector, restricted to the services the
/// app drives (CardDAV, JMAP).
fn search_merge(lists: &str) -> Result<String, String> {
    let lists: Vec<Vec<ServiceConfig>> =
        serde_json::from_str(lists).map_err(|err| format!("Invalid config lists: {err}"))?;

    let services = BTreeSet::from([Service::Carddav, Service::Jmap]);
    let mut collector = ConfigCollector::new(services);

    for configs in lists {
        collector.collect(configs);
    }

    serde_json::to_string(&collector.finish()).map_err(|err| err.to_string())
}

/// Reads a Java string, defaulting to empty on any conversion error.
fn read_string(env: &Env, value: &JString) -> String {
    value.try_to_string(env).unwrap_or_default()
}

/// Reads and parses a store verb's JSON facts; the error side is the
/// ready-to-return error reply.
fn parse_facts(env: &Env, facts: &JString) -> Result<serde_json::Value, String> {
    let raw = read_string(env, facts);
    serde_json::from_str(&raw).map_err(|err| error_json(format!("Invalid store facts: {err}")))
}

/// Serializes a store decision, or its error.
fn decision_json(decision: Result<serde_json::Value, String>) -> String {
    match decision {
        Ok(value) => value.to_string(),
        Err(err) => error_json(err),
    }
}

/// Serializes a placement decision (`{"placement": {..} | null}`), or
/// its error.
fn placement_json(decision: Result<Option<serde_json::Value>, String>) -> String {
    match decision {
        Ok(placement) => {
            serde_json::json!({ "placement": placement.unwrap_or(serde_json::Value::Null) })
                .to_string()
        }
        Err(err) => error_json(err),
    }
}

fn parse_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}"))
}

/// Parses a JSON string array of book ids (empty or null means none).
fn parse_books(raw: &str) -> Result<Vec<String>, String> {
    if raw.is_empty() {
        return Ok(Vec::new());
    }
    serde_json::from_str(raw).map_err(|err| format!("Invalid book id list `{raw}`: {err}"))
}

/// Parses a JSON string array of vCards.
fn parse_strings(raw: &str) -> Result<Vec<String>, String> {
    serde_json::from_str(raw).map_err(|err| format!("Invalid vCard list: {err}"))
}

/// Parses a JSON array of `{"ref", "vcard"}` pairs.
fn parse_ref_cards(raw: &str) -> Result<Vec<(String, String)>, String> {
    let value: serde_json::Value =
        serde_json::from_str(raw).map_err(|err| format!("Invalid card list: {err}"))?;
    let Some(entries) = value.as_array() else {
        return Err("Invalid card list: not an array".into());
    };

    let mut cards = Vec::with_capacity(entries.len());
    for entry in entries {
        let reference = entry
            .get("ref")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("")
            .to_string();
        let vcard = entry
            .get("vcard")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("")
            .to_string();
        cards.push((reference, vcard));
    }
    Ok(cards)
}

/// Serializes any backend's delta.
fn delta_json(delta: CardDelta) -> String {
    serde_json::to_string(&delta).unwrap_or_else(|err| error_json(err.to_string()))
}

/// Public-client registration params: a loopback redirect, no secret,
/// the code and refresh grants the app drives.
fn oauth_register_params(
    redirect_uri: &str,
    client_name: &str,
    scope: &str,
) -> Oauth20RegisterClientParams {
    Oauth20RegisterClientParams {
        redirect_uris: vec![redirect_uri.to_string()],
        token_endpoint_auth_method: Some("none".to_string()),
        grant_types: vec![
            "authorization_code".to_string(),
            "refresh_token".to_string(),
        ],
        response_types: vec!["code".to_string()],
        client_name: (!client_name.is_empty()).then(|| client_name.to_string()),
        scope: (!scope.is_empty()).then(|| scope.to_string()),
        ..Default::default()
    }
}

/// Serializes the issued client information, exposing the secret only
/// when the server returned one.
// SAFETY: exposes the client secret, which a public-client
// registration should not carry.
fn client_information_json(client: &Oauth20ClientInformation) -> String {
    use secrecy::ExposeSecret;

    let secret = client
        .client_secret
        .as_ref()
        .map(|secret| secret.expose_secret());
    serde_json::json!({
        "client_id": client.client_id,
        "client_secret": secret,
    })
    .to_string()
}

/// Serializes a failure as the error reply, `{"error": message}` plus
/// a `status` field when the failure was an HTTP round (the type is
/// the wire shape; plain strings convert to status-less errors).
fn error_json(error: impl Into<BridgeError>) -> String {
    serde_json::to_string(&error.into())
        .unwrap_or_else(|_| r#"{"error": "Unserializable bridge failure"}"#.to_string())
}
