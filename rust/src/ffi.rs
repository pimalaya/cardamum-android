//! JNI entry points called from the Cardamum `:client` module.
//!
//! Every method captures the FFI [`EnvUnowned`], upgrades it to a usable
//! [`Env`] inside [`EnvUnowned::with_env`] (which also guards against
//! unwinding across the JNI boundary), and resolves the outcome with
//! [`LogErrorAndDefault`] so an unexpected JNI failure logs and returns a
//! null string rather than throwing. Every reply is JSON; failures are
//! `{"error": ".."}`.

use jni::{
    Env, EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use url::Url;

use crate::{
    client::Client,
    oauth::{authorize_url, validate_redirect},
    project::{apply, index, merge_cards, merge_conflict, project, set_uid},
    types::Credentials,
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
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchAll`: collects CardDAV service configs (endpoint,
/// username, authentication methods, source mechanism) from every
/// pimconf mechanism: fixed provider rules, PACC, RFC 6764 resolve.
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchAll<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_json(env, &transport, &email, &resolver, false);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchFirst`: same mechanism chain as `searchAll`, stopping
/// at the first mechanism yielding a config. Returns a JSON array of
/// service configs (empty when nothing was found).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchFirst<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_json(env, &transport, &email, &resolver, true);
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
            Err(err) => error_json(&err),
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
            Err(err) => error_json(&err),
        };

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
            Err(err) => error_json(&err),
            Ok(url) => match Client::new(env, &transport).oauth_request_access_token(
                &url,
                &client_id,
                secret,
                &code,
                &redirect_uri,
                &pkce_verifier,
            ) {
                Ok(tokens) => serde_json::to_string(&tokens)
                    .unwrap_or_else(|err| error_json(&err.to_string())),
                Err(err) => error_json(&err),
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
            Err(err) => error_json(&err),
            Ok(url) => match Client::new(env, &transport).oauth_refresh_access_token(
                &url,
                &client_id,
                secret,
                &refresh_token,
                &scope,
            ) {
                Ok(tokens) => serde_json::to_string(&tokens)
                    .unwrap_or_else(|err| error_json(&err.to_string())),
                Err(err) => error_json(&err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listAddressbooks`: walks current-user-principal ->
/// addressbook-home-set -> list from the discovered context root.
/// Returns a JSON array of addressbooks.
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

        let json = match parse_url(&base_url) {
            Err(err) => error_json(&err),
            Ok(base_url) => {
                match Client::new(env, &transport).list_addressbooks(&base_url, &credentials) {
                    Ok(books) => serde_json::to_string(&books)
                        .unwrap_or_else(|err| error_json(&err.to_string())),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listCards`: lists the cards of the addressbook collection.
/// Returns a JSON array of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listCards<'local>(
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
            Err(err) => error_json(&err),
            Ok(url) => match Client::new(env, &transport).list_cards(&url, &credentials) {
                Ok(cards) => {
                    serde_json::to_string(&cards).unwrap_or_else(|err| error_json(&err.to_string()))
                }
                Err(err) => error_json(&err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createCard`: creates a card in the addressbook collection;
/// the server resource is named `<id>.vcf`. Returns
/// `{"etag": ".." | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&addressbook_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                match Client::new(env, &transport).create_card(&url, &credentials, &id, &vcard) {
                    Ok(etag) => etag_json(etag),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readCard`: reads the card at the given resource name (as the
/// server returned it). Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_readCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uri: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uri = read_string(env, &uri);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&addressbook_url) {
            Err(err) => error_json(&err),
            Ok(url) => match Client::new(env, &transport).read_card(&url, &credentials, &uri) {
                Ok(card) => {
                    serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
                }
                Err(err) => error_json(&err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateCard`: updates the card at the given resource name (as
/// the server returned it), guarded by the ETag when one is passed
/// (empty means unknown). Returns `{"etag": ".." | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uri: JString<'local>,
    vcard: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uri = read_string(env, &uri);
        let vcard = read_string(env, &vcard);
        let etag = read_string(env, &etag);
        let if_match = (!etag.is_empty()).then_some(etag.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&addressbook_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                let mut client = Client::new(env, &transport);
                match client.update_card(&url, &credentials, &uri, &vcard, if_match) {
                    Ok(etag) => etag_json(etag),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteCard`: deletes the card at the given resource name
/// (as the server returned it), guarded by the ETag when one is passed
/// (empty means unknown). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uri: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uri = read_string(env, &uri);
        let etag = read_string(env, &etag);
        let if_match = (!etag.is_empty()).then_some(etag.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&addressbook_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                match Client::new(env, &transport).delete_card(&url, &credentials, &uri, if_match) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listGraphAddressbooks`: lists the Microsoft Graph contact
/// folders (default Contacts folder first, empty id) as addressbooks.
/// Returns a JSON array; collection URLs are empty, the caller
/// composes them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listGraphAddressbooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);

        let json = match Client::new(env, &transport).list_graph_addressbooks(&token) {
            Ok(books) => {
                serde_json::to_string(&books).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listGraphCards`: lists the contacts of the Graph folder
/// (empty id means the default Contacts folder), each projected onto
/// a vCard. Returns a JSON array of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listGraphCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    folder: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let folder = read_string(env, &folder);

        let json = match Client::new(env, &transport).list_graph_cards(&token, &folder) {
            Ok(cards) => {
                serde_json::to_string(&cards).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createGraphCard`: creates the vCard as a Graph contact in
/// the folder (empty id means the default Contacts folder). Graph
/// names the resource, so the returned `{id, uri, etag, vcard}` card
/// carries the server-assigned id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createGraphCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    folder: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let folder = read_string(env, &folder);
        let vcard = read_string(env, &vcard);

        let json = match Client::new(env, &transport).create_graph_card(&token, &folder, &vcard) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readGraphCard`: reads the Graph contact at the given id,
/// projected onto a vCard. Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_readGraphCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);

        let json = match Client::new(env, &transport).read_graph_card(&token, &id) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateGraphCard`: updates the Graph contact at the given id
/// from the vCard, PATCHing only the fields that differ from the base
/// vCard when one is passed (empty means unknown; no If-Match guard,
/// last-write-wins). Returns the updated `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateGraphCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
    base_vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let base_vcard = read_string(env, &base_vcard);
        let base = (!base_vcard.is_empty()).then_some(base_vcard.as_str());

        let json = match Client::new(env, &transport).update_graph_card(&token, &id, &vcard, base) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteGraphCard`: deletes the Graph contact at the given
/// id. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteGraphCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);

        let json = match Client::new(env, &transport).delete_graph_card(&token, &id) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listJmapAddressbooks`: lists the JMAP AddressBooks
/// (RFC 9610) of the account behind the session URL. Returns a JSON
/// array; collection URLs are empty, the caller composes them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listJmapAddressbooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&session_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                match Client::new(env, &transport).list_jmap_addressbooks(&url, &credentials) {
                    Ok(books) => serde_json::to_string(&books)
                        .unwrap_or_else(|err| error_json(&err.to_string())),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listJmapCards`: lists the account's ContactCards across
/// every JMAP AddressBook, each converted to a vCard. Returns a JSON
/// array of `{id, uri, etag, vcard, books}` objects (ContactCard id as
/// id and uri, a JSON hash as etag, the addressBookIds as books).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listJmapCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&session_url) {
            Err(err) => error_json(&err),
            Ok(url) => match Client::new(env, &transport).list_jmap_cards(&url, &credentials) {
                Ok(cards) => {
                    serde_json::to_string(&cards).unwrap_or_else(|err| error_json(&err.to_string()))
                }
                Err(err) => error_json(&err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createJmapCard`: creates the vCard as a ContactCard in the
/// JMAP AddressBook. The server names the card, so the returned
/// `{id, uri, etag, vcard}` carries the server-assigned id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createJmapCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    book: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let book = read_string(env, &book);
        let vcard = read_string(env, &vcard);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&session_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                match Client::new(env, &transport).create_jmap_card(
                    &url,
                    &credentials,
                    &book,
                    &vcard,
                ) {
                    Ok(card) => serde_json::to_string(&card)
                        .unwrap_or_else(|err| error_json(&err.to_string())),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readJmapCard`: reads the ContactCard at the given id,
/// converted to a vCard. Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_readJmapCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&session_url) {
            Err(err) => error_json(&err),
            Ok(url) => match Client::new(env, &transport).read_jmap_card(&url, &credentials, &id) {
                Ok(card) => {
                    serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
                }
                Err(err) => error_json(&err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateJmapCard`: updates the ContactCard at the given id
/// from the vCard, patching only the properties that differ from the
/// base vCard when one is passed (empty means unknown; no If-Match
/// guard, last-write-wins). Returns the updated `{id, uri, etag,
/// vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateJmapCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
    base_vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let base_vcard = read_string(env, &base_vcard);
        let base = (!base_vcard.is_empty()).then_some(base_vcard.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&session_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                match Client::new(env, &transport).update_jmap_card(
                    &url,
                    &credentials,
                    &id,
                    &vcard,
                    base,
                ) {
                    Ok(card) => serde_json::to_string(&card)
                        .unwrap_or_else(|err| error_json(&err.to_string())),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteJmapCard`: destroys the ContactCard at the given id.
/// Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteJmapCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&session_url) {
            Err(err) => error_json(&err),
            Ok(url) => {
                match Client::new(env, &transport).delete_jmap_card(&url, &credentials, &id) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listGoogleAddressbooks`: lists the Google account's contacts
/// as one Contacts addressbook (the People API has no folders), after a
/// one-person listing validated the token. Returns a JSON array; the
/// collection URL is empty, the caller composes it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listGoogleAddressbooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);

        let json = match Client::new(env, &transport).list_google_addressbooks(&token) {
            Ok(books) => {
                serde_json::to_string(&books).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listGoogleCards`: lists the account's People contacts, each
/// projected onto a vCard. Returns a JSON array of `{id, uri, etag,
/// vcard}` objects (person id as id and uri, person etag as etag).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listGoogleCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);

        let json = match Client::new(env, &transport).list_google_cards(&token) {
            Ok(cards) => {
                serde_json::to_string(&cards).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createGoogleCard`: creates the vCard as a People contact.
/// The server names the resource, so the returned `{id, uri, etag,
/// vcard}` card carries the server-assigned id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createGoogleCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let vcard = read_string(env, &vcard);

        let json = match Client::new(env, &transport).create_google_card(&token, &vcard) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readGoogleCard`: reads the People contact at the given id,
/// projected onto a vCard. Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_readGoogleCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);

        let json = match Client::new(env, &transport).read_google_card(&token, &id) {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateGoogleCard`: updates the People contact at the given
/// id from the vCard, shrinking the update mask to the fields that
/// differ from the base vCard when one is passed (empty means unknown).
/// People guards updates with the person etag (empty fetches it first).
/// Returns the updated `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateGoogleCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
    base_vcard: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let base_vcard = read_string(env, &base_vcard);
        let base = (!base_vcard.is_empty()).then_some(base_vcard.as_str());
        let etag = read_string(env, &etag);
        let etag = (!etag.is_empty()).then_some(etag.as_str());

        let json = match Client::new(env, &transport)
            .update_google_card(&token, &id, &vcard, base, etag)
        {
            Ok(card) => {
                serde_json::to_string(&card).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteGoogleCard`: deletes the People contact at the given
/// id. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteGoogleCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);

        let json = match Client::new(env, &transport).delete_google_card(&token, &id) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(&err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateJmapCardBooks`: adds and removes AddressBook
/// memberships of the ContactCard at the given id (JSON string arrays
/// of book ids). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateJmapCardBooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    session_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    add: JString<'local>,
    remove: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let session_url = read_string(env, &session_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let add = read_string(env, &add);
        let remove = read_string(env, &remove);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match (
            parse_url(&session_url),
            parse_books(&add),
            parse_books(&remove),
        ) {
            (Err(err), _, _) | (_, Err(err), _) | (_, _, Err(err)) => error_json(&err),
            (Ok(url), Ok(add), Ok(remove)) => {
                match Client::new(env, &transport).update_jmap_card_books(
                    &url,
                    &credentials,
                    &id,
                    &add,
                    &remove,
                ) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(&err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateGoogleCardBooks`: adds and removes the People contact
/// at the given id from contact groups (JSON string arrays of group
/// ids). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateGoogleCardBooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    id: JString<'local>,
    add: JString<'local>,
    remove: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let id = read_string(env, &id);
        let add = read_string(env, &add);
        let remove = read_string(env, &remove);

        let json = match (parse_books(&add), parse_books(&remove)) {
            (Err(err), _) | (_, Err(err)) => error_json(&err),
            (Ok(add), Ok(remove)) => {
                match Client::new(env, &transport)
                    .update_google_card_books(&token, &id, &add, &remove)
                {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(&err),
                }
            }
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
            Err(err) => error_json(&err),
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
            Err(err) => error_json(&err),
            Ok(cards) => match merge_cards(&cards) {
                Ok(merged) => merged.to_string(),
                Err(err) => error_json(&err),
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
            Err(err) => error_json(&err),
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
            Err(err) => error_json(&err),
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
            Err(err) => error_json(&err),
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
            Err(err) => error_json(&format!("Invalid field model: {err}")),
            Ok(model) => match apply(&vcard, &model) {
                Ok(patched) => serde_json::json!({ "vcard": patched }).to_string(),
                Err(err) => error_json(&err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Runs one search (all or first mechanisms) and serializes the
/// resulting service configs.
fn search_json<'local>(
    env: &mut Env<'local>,
    transport: &JObject<'local>,
    email: &JString<'local>,
    resolver: &JString<'local>,
    first: bool,
) -> String {
    let email = read_string(env, email);
    let resolver = read_string(env, resolver);
    let resolver = (!resolver.is_empty()).then_some(resolver.as_str());

    match Client::new(env, transport).search(&email, resolver, first) {
        Ok(configs) => {
            serde_json::to_string(&configs).unwrap_or_else(|err| error_json(&err.to_string()))
        }
        Err(err) => error_json(&err),
    }
}

/// Reads a Java string, defaulting to empty on any conversion error.
fn read_string(env: &Env, value: &JString) -> String {
    value.try_to_string(env).unwrap_or_default()
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

fn etag_json(etag: Option<String>) -> String {
    serde_json::json!({ "etag": etag }).to_string()
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}
