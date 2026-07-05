//! JNI bridge for the Cardamum Android :client module.
//!
//! TLS and TCP live in Java (SSLSocket); this crate is a pure protocol
//! state machine running pimconf's RFC 6764 discovery and io-webdav's
//! sans-io CardDAV coroutines, doing all socket I/O by upcalling a Java
//! `Transport` on each yield. It exposes one entry point per operation:
//! `discover` resolves an email to a CardDAV context root,
//! `listAddressbooks` walks current-user-principal ->
//! addressbook-home-set -> list (doubling as the onboarding connection
//! check), and `listCards` / `createCard` / `updateCard` / `deleteCard`
//! are the vCard CRUD the contact screens build on.

mod client;
mod ffi;
mod project;
mod types;
