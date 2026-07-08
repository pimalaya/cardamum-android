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
//! ContactCards to and from vCards via calcard (RFC 9555). Google
//! accounts can skip CardDAV too: the `listGoogle*` / `*GoogleCard`
//! entry points run io-google-people's contact coroutines, with the
//! google module projecting People person resources to and from
//! vCards. The `offline*` entry points run io-offline's replica engine
//! (sync, upgrade, mutate), upcalling a Java `OfflineDriver` on each
//! yield so storage stays in the Java CardStore and remote operations
//! reuse the backend clients; `enumCards`, `syncCards` and
//! `multigetCards` are the CardDAV primitives its remote seam builds
//! on (RFC 6578 sync-collection with a full-enumeration fallback, and
//! addressbook-multiget body fetches).

mod client;
mod ffi;
mod google;
mod jmap;
mod msgraph;
mod oauth;
mod offline;
mod project;
mod types;
