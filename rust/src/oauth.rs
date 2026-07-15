//! OAuth 2.0 helpers on top of io-oauth.
//!
//! Authorization URL building, redirect validation, session-string
//! generation and scope negotiation are pure computations (no
//! transport); the token exchanges live on [`crate::client::Client`]
//! since they run HTTP coroutines.

use std::{borrow::Cow, collections::BTreeMap, str::FromStr};

use rand::{rng, seq::IndexedRandom};

use io_oauth::{
    rfc6749::{
        auth_request::Oauth20AuthRequestParams, auth_response::Oauth20AuthParams,
        state::Oauth20State,
    },
    rfc7636::pkce::{
        Oauth20PkceCodeChallenge, Oauth20PkceCodeChallengeMethod, Oauth20PkceCodeVerifier,
    },
};
use serde::{
    Deserialize,
    de::value::{Error as DeserializeError, StrDeserializer},
};
use serde_json::from_str;
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
        from_str(extras).map_err(|err| format!("Invalid OAuth extras JSON: {err}"))?
    };

    let params = Oauth20AuthRequestParams {
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

    Oauth20AuthParams::from(&url)
        .validate(Some(&state))
        .map(|code| code.into_owned())
        .map_err(|err| err.to_string())
}

/// Generates one authorization session's CSRF state (32 chars) and
/// PKCE verifier (64 chars), both alphanumeric.
pub fn session_params() -> (String, String) {
    (random_string(32), random_string(64))
}

/// The scope a contacts client requests of an authorization server:
/// the standard contacts scope (draft-ietf-mailmaint-oauth-public)
/// plus `offline_access` for the refresh token, each kept only when
/// the space-separated advertised set contains it. Requesting the
/// whole advertised set instead is what an "invalid scope"
/// authorization error comes from: that set is what the server
/// supports, not what a contacts client needs. A server advertising
/// no scopes at all gets the standard pair as-is.
pub fn contacts_scope(scopes_supported: &str) -> String {
    const WANTED: [&str; 2] = ["urn:ietf:params:oauth:scope:contacts", "offline_access"];

    let advertised: Vec<&str> = scopes_supported.split_whitespace().collect();
    if advertised.is_empty() {
        return WANTED.join(" ");
    }

    WANTED
        .into_iter()
        .filter(|wanted| advertised.contains(wanted))
        .collect::<Vec<&str>>()
        .join(" ")
}

/// Parses the session PKCE verifier, rejecting bytes outside the
/// RFC 7636 unreserved set.
pub fn parse_pkce_verifier(verifier: &str) -> Result<Oauth20PkceCodeVerifier, String> {
    Oauth20PkceCodeVerifier::from_str(verifier)
        .map_err(|byte| format!("Invalid PKCE verifier byte 0x{byte:x}"))
}

/// Letters and digits only, a strict subset of the RFC 7636 unreserved
/// set, valid for both the PKCE verifier and the RFC 6749 VSCHAR
/// state. The full set also allows `-._~`, which no server can be
/// trusted to accept everywhere; 62 symbols keep ample entropy.
const ALPHANUMERIC: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

/// One cryptographically random alphanumeric string of the given size.
fn random_string(size: usize) -> String {
    let mut rng = rng();

    (0..size)
        .map(|_| *ALPHANUMERIC.choose(&mut rng).expect("non-empty charset") as char)
        .collect()
}

/// Parses the session CSRF state, rejecting bytes outside the RFC 6749
/// VSCHAR set.
fn parse_state(state: &str) -> Result<Oauth20State, String> {
    let deserializer = StrDeserializer::<DeserializeError>::new(state);
    Oauth20State::deserialize(deserializer).map_err(|err| format!("Invalid OAuth state: {err}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The issuer-flow authorization URL, byte for byte, for
    /// fastmail-shaped inputs; guards the exact wire format the
    /// authorize endpoint validates (parameter order, percent
    /// encoding, space-as-plus in the scope).
    #[test]
    fn authorize_url_matches_the_verified_fastmail_shape() {
        let url = authorize_url(
            "https://api.fastmail.com/oauth/authorize",
            "07ee41ae",
            "org.pimalaya.cardamum:/oauth2redirect",
            "urn:ietf:params:oauth:scope:contacts offline_access",
            "stateABCDEF0123456789abcdef012345",
            "verifierABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef01",
            "",
        )
        .unwrap();

        assert_eq!(
            url,
            "https://api.fastmail.com/oauth/authorize\
             ?client_id=07ee41ae\
             &code_challenge=3UabJaVjZLMdt78g6JRyEM8pdmTSqJNYLL3y6RSjDr8\
             &code_challenge_method=S256\
             &redirect_uri=org.pimalaya.cardamum%3A%2Foauth2redirect\
             &response_type=code\
             &scope=offline_access+urn%3Aietf%3Aparams%3Aoauth%3Ascope%3Acontacts\
             &state=stateABCDEF0123456789abcdef012345",
        );
    }

    /// The fastmail-shaped success redirect on the RFC 8252
    /// single-slash private-use scheme (no authority), with the
    /// RFC 9207 iss parameter riding along, yields the code.
    #[test]
    fn validate_redirect_accepts_the_single_slash_scheme() {
        let code = validate_redirect(
            "org.pimalaya.cardamum:/oauth2redirect\
             ?code=abc123\
             &state=stateABCDEF0123456789abcdef012345\
             &iss=https%3A%2F%2Fapi.fastmail.com",
            "stateABCDEF0123456789abcdef012345",
        );

        assert_eq!(code.unwrap(), "abc123");
    }

    /// The generated session strings have the documented sizes, stay
    /// alphanumeric, and validate as state and verifier.
    #[test]
    fn session_params_generate_valid_strings() {
        let (state, verifier) = session_params();

        assert_eq!(state.len(), 32);
        assert_eq!(verifier.len(), 64);
        assert!(state.bytes().all(|byte| byte.is_ascii_alphanumeric()));
        assert!(verifier.bytes().all(|byte| byte.is_ascii_alphanumeric()));
        assert!(parse_state(&state).is_ok());
        assert!(parse_pkce_verifier(&verifier).is_ok());
    }

    /// The requested scope is the intersection of the standard pair
    /// with the advertised set; no advertised set requests the pair
    /// as-is.
    #[test]
    fn contacts_scope_negotiates_against_the_advertised_set() {
        assert_eq!(
            contacts_scope(""),
            "urn:ietf:params:oauth:scope:contacts offline_access",
        );
        assert_eq!(
            contacts_scope("mail urn:ietf:params:oauth:scope:contacts calendars"),
            "urn:ietf:params:oauth:scope:contacts",
        );
        assert_eq!(
            contacts_scope("urn:ietf:params:oauth:scope:contacts offline_access extras"),
            "urn:ietf:params:oauth:scope:contacts offline_access",
        );
        assert_eq!(contacts_scope("mail calendars"), "");
    }

    /// An error redirect surfaces the server's error code.
    #[test]
    fn validate_redirect_surfaces_the_server_error() {
        let err = validate_redirect(
            "org.pimalaya.cardamum:/oauth2redirect\
             ?error=invalid_request\
             &state=stateABCDEF0123456789abcdef012345",
            "stateABCDEF0123456789abcdef012345",
        )
        .unwrap_err();

        assert_eq!(err, "Authorization error: InvalidRequest");
    }
}
