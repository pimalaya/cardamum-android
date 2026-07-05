//! OAuth 2.0 helpers on top of io-oauth.
//!
//! Authorization URL building and redirect validation are pure
//! computations (no transport); the token exchanges live on
//! [`crate::client::Client`] since they drive HTTP coroutines. The
//! CSRF state and PKCE verifier strings are generated on the Java
//! side (SecureRandom) and validated here on every use.

use std::{borrow::Cow, collections::BTreeMap, str::FromStr};

use io_oauth::v2_0::authorization_code_grant::{
    authorization_request::Oauth20AuthorizationRequestParams,
    authorization_response::Oauth20AuthorizeParams,
    pkce::{Oauth20PkceCodeChallenge, Oauth20PkceCodeChallengeMethod, Oauth20PkceCodeVerifier},
    state::Oauth20State,
};
use serde::{
    Deserialize,
    de::value::{Error as DeserializeError, StrDeserializer},
};
use url::Url;

/// Builds the RFC 6749 §4.1.1 authorization URL with PKCE (S256) and
/// CSRF state. `extras` carries provider-specific query parameters as
/// a JSON object string (e.g. Google's access_type=offline and
/// prompt=consent, without which no refresh token is issued); empty
/// means none.
pub fn authorize_url(
    authorization_endpoint: &str,
    client_id: &str,
    redirect_uri: &str,
    scope: &str,
    state: &str,
    pkce_verifier: &str,
    extras: &str,
) -> Result<String, String> {
    let endpoint = Url::parse(authorization_endpoint).map_err(|err| {
        format!("Invalid authorization endpoint `{authorization_endpoint}`: {err}")
    })?;

    let state = parse_state(state)?;
    let verifier = parse_pkce_verifier(pkce_verifier)?;

    let challenge = Oauth20PkceCodeChallenge {
        method: Oauth20PkceCodeChallengeMethod::Sha256,
        verifier,
    };

    let extras: BTreeMap<String, String> = if extras.is_empty() {
        BTreeMap::new()
    } else {
        serde_json::from_str(extras).map_err(|err| format!("Invalid OAuth extras JSON: {err}"))?
    };

    let params = Oauth20AuthorizationRequestParams {
        client_id: client_id.into(),
        redirect_uri: Some(redirect_uri.into()),
        scope: scope.split_whitespace().map(Cow::Borrowed).collect(),
        state: Some(Cow::Owned(state)),
        pkce_code_challenge: Some(Cow::Owned(challenge)),
        extras: extras
            .into_iter()
            .map(|(key, value)| (Cow::Owned(key), Cow::Owned(value)))
            .collect(),
    };

    Ok(params.build_url(&endpoint).to_string())
}

/// Validates the RFC 6749 §4.1.2 authorization redirect against the
/// expected CSRF state and returns the authorization code.
pub fn validate_redirect(redirect_url: &str, state: &str) -> Result<String, String> {
    let url = Url::parse(redirect_url)
        .map_err(|err| format!("Invalid redirect URL `{redirect_url}`: {err}"))?;

    let state = parse_state(state)?;

    Oauth20AuthorizeParams::from(&url)
        .validate(Some(&state))
        .map(|code| code.into_owned())
        .map_err(|err| err.to_string())
}

/// Parses the Java-generated PKCE verifier, rejecting bytes outside
/// the RFC 7636 unreserved set.
pub fn parse_pkce_verifier(verifier: &str) -> Result<Oauth20PkceCodeVerifier, String> {
    Oauth20PkceCodeVerifier::from_str(verifier)
        .map_err(|byte| format!("Invalid PKCE verifier byte 0x{byte:x}"))
}

/// Parses the Java-generated CSRF state, rejecting bytes outside the
/// RFC 6749 VSCHAR set.
fn parse_state(state: &str) -> Result<Oauth20State, String> {
    let deserializer = StrDeserializer::<DeserializeError>::new(state);
    Oauth20State::deserialize(deserializer).map_err(|err| format!("Invalid OAuth state: {err}"))
}
