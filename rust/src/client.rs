//! Blocking CardDAV client over the JNI transport: drives io-pim-discovery's
//! RFC 6764 discovery and io-webdav's sans-io CardDAV coroutines, doing
//! all socket I/O by upcalling the Java `Transport` on each yield. TLS
//! lives in Java; this crate only ever sees plaintext bytes.
//!
//! Session state is not cached: each native call builds a client, runs
//! one operation, and drops it. The Java transport owns the sockets,
//! one per origin (DNS resolver, HTTPS servers), so a single discovery
//! or CardDAV cycle can talk to several endpoints; the bridge only
//! tells it which URL each yield wants.
//!
//! The struct and its transport layer (`read` / `write`, the generic
//! coroutine runners `run` / `run_redirect`) live here; each backend's
//! operations sit in a submodule, reached as extra `impl Client` blocks
//! through `crate::client::...`. The free `to_card` / `to_addressbook`
//! and error helpers shared by more than one backend live in
//! [`convert`].

mod carddav;
mod convert;
mod discovery;
mod dispatch;
mod google;
mod graph;
mod jmap;

use core::error::Error as StdError;

use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::coroutine::WebdavRedirectYield,
};
use jni::{
    Env, JValue,
    errors::Error,
    jni_sig, jni_str,
    objects::{JByteArray, JObject},
};
use url::Url;

use crate::{client::convert::coroutine_error, types::BridgeError};

/// Sent as the `User-Agent` on every WebDAV request; shared by the
/// CardDAV verbs and the discovery walk.
pub(crate) const USER_AGENT: &str = concat!("cardamum-android/", env!("CARGO_PKG_VERSION"));

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
}

/// Coroutine runners shared by more than one backend: the resume loops
/// that pump a sans-io WebDAV coroutine, routing every read and write
/// yield to the Java transport stream opened for the target URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Drives a plain (non-redirect) coroutine against `target`.
    pub(crate) fn run<C, T, E>(&mut self, target: &Url, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: WebdavCoroutine<Yield = WebdavYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(value)) => return Ok(value),
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

    /// Drives a redirect-aware coroutine, rebuilding it against the
    /// new target whenever the server answers 3xx; the transport pools
    /// one connection per origin, so a cross-origin redirect just
    /// opens a new socket.
    pub(crate) fn run_redirect<C, T, E>(
        &mut self,
        start: &Url,
        make: impl Fn(&Url) -> C,
    ) -> Result<T, BridgeError>
    where
        C: WebdavCoroutine<Yield = WebdavRedirectYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut target = start.clone();

        loop {
            let mut coroutine = make(&target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    WebdavCoroutineState::Complete(Ok(value)) => return Ok(value),
                    WebdavCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
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
                        target = url;
                        break;
                    }
                }
            }
        }
    }
}

/// Transport upcalls: the JNI bridge to the Java `Transport`, reading
/// and writing bytes on the stream open for a given URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Upcalls the Java transport to read the next chunk from the
    /// stream open on `url`.
    pub(crate) fn read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
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
            .map_err(|err| err.to_string().into())
    }

    /// Upcalls the Java transport to write and flush bytes to the
    /// stream open on `url`.
    pub(crate) fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
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

/// Catches and clears any pending Java exception, surfacing its class
/// and message (a bare JNI error only says "Java exception was thrown").
/// The lowercase op is capitalized so the user-facing message reads as a
/// sentence.
pub(crate) fn clear_and_fail(env: &mut Env, op: &str, err: Error) -> String {
    let mut chars = op.chars();
    let op = match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    };

    match env.exception_catch() {
        Err(Error::CaughtJavaException { name, msg, .. }) => {
            format!("{op} failed: {name}: {msg}")
        }
        _ => format!("{op} failed: {err}"),
    }
}

#[cfg(test)]
mod tests {
    use crate::client::{carddav::normalize_vcard, discovery::search_domain};

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

    /// The CardDAV write path repairs a vCard 3.0 that lacks the
    /// mandatory `N` via vcard-rs; an already valid card round-trips
    /// unchanged, and unparseable input passes straight through. The
    /// injection itself is covered in vcard-rs.
    #[test]
    fn normalize_vcard_supplies_the_mandatory_n() {
        use std::borrow::Cow;

        let missing = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:x\r\nFN:Only\r\nEND:VCARD\r\n";
        assert!(normalize_vcard(missing).contains("N:;;;;\r\n"));

        let with_n = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nFN:Jane\r\nEND:VCARD\r\n";
        assert_eq!(&*normalize_vcard(with_n), with_n);

        assert!(matches!(normalize_vcard("not a vcard"), Cow::Borrowed(_)));
    }
}
