//! OAuth (RFC 6749/7591/8414) JNI entry points.

use io_oauth::rfc7591::register::{Oauth20ClientInformation, Oauth20ClientRegisterParams};
use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::{json, to_string};

use crate::{
    client::Client,
    ffi::{error_json, parse_url, read_string},
    oauth::{authorize_url, contacts_scope, session_params, validate_redirect},
};

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
            Ok(url) => json!({ "url": url }).to_string(),
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
            Ok(code) => json!({ "code": code }).to_string(),
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
        let json = json!({ "state": state, "verifier": verifier }).to_string();

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
        let json = json!({ "scope": scope }).to_string();

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
                Ok(tokens) => to_string(&tokens).unwrap_or_else(|err| error_json(err.to_string())),
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
                Ok(metadata) => {
                    to_string(&metadata).unwrap_or_else(|err| error_json(err.to_string()))
                }
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
                Ok(tokens) => to_string(&tokens).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Public-client registration params: a loopback redirect, no secret,
/// the code and refresh grants the app drives.
fn oauth_register_params(
    redirect_uri: &str,
    client_name: &str,
    scope: &str,
) -> Oauth20ClientRegisterParams {
    Oauth20ClientRegisterParams {
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
// NOTE: exposes the client secret, which a public-client registration
// should not carry.
fn client_information_json(client: &Oauth20ClientInformation) -> String {
    use secrecy::ExposeSecret;

    let secret = client
        .client_secret
        .as_ref()
        .map(|secret| secret.expose_secret());
    json!({
        "client_id": client.client_id,
        "client_secret": secret,
    })
    .to_string()
}
