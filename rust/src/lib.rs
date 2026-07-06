//! JNI bridge for the Cardamum Android :client module.
//!
//! TLS and TCP live in Java (SSLSocket); this crate is a pure protocol
//! state machine running pimconf's discovery, io-oauth's OAuth 2.0 and
//! io-webdav's sans-io CardDAV coroutines, doing all socket I/O by
//! upcalling a Java `Transport` on each yield. It exposes one entry
//! point per operation: `search` turns an email into CardDAV service
//! configs with their authentication methods (`discover` is the older
//! context-root-only flavor), the `oauth*` entry points cover the
//! authorization code grant with PKCE (authorize URL, redirect
//! validation, code exchange, token refresh), `listAddressbooks` walks
//! current-user-principal -> addressbook-home-set -> list (doubling as
//! the onboarding connection check), and `listCards` / `createCard` /
//! `updateCard` / `deleteCard` are the vCard CRUD the contact screens
//! build on. Microsoft accounts expose neither CardDAV nor any vCard
//! representation, so the `listGraph*` / `*GraphCard` entry points
//! run io-msgraph's contact coroutines instead, with the msgraph
//! module projecting Graph contact resources to and from the vCard
//! document of record. JMAP servers likewise speak JSContact instead
//! of vCard, so the `listJmap*` / `*JmapCard` entry points run
//! io-jmap's RFC 9610 coroutines, with the jmap module converting
//! ContactCards to and from vCards via calcard (RFC 9555).

mod client;
mod ffi;
mod jmap;
mod msgraph;
mod oauth;
mod project;
mod types;
