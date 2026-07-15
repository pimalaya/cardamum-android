# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added multi-account contact management over CardDAV, JMAP for Contacts, Microsoft Graph and the Google People API, unified behind one client and a contact-first view that merges every account's addressbooks.
- Added offline-first storage: a full local vCard store rendered instantly, with edits staged and pushed on the next sync.
- Added incremental synchronization on every backend through the io-offline replica engine, with three-way-merge conflicts that auto-resolve clean divergences and surface only genuine same-field collisions for manual resolution.
- Added two-way phone synchronization: each addressbook mirrors into its own Android account (the DAVx5 pattern), so edits from any contacts app converge into the store and ride upstream.
- Added scheduled background synchronization per addressbook (WorkManager), from every fifteen minutes to daily, reporting each pass in a notification.
- Added automatic setup from an email address or a bare domain: parallel provider-rule (MX), PACC, CardDAV and JMAP discovery over a DNS-over-HTTPS resolver, with a standard or advanced onboarding path.
- Added authentication by password, API token or OAuth 2.0, with shipped Google and Microsoft clients, custom clients, and zero-registration OAuth (metadata discovery, dynamic client registration and PKCE); credentials are encrypted in the Android Keystore.
- Added a full vCard 4.0 editor: a form covering every form-worthy property, an advanced per-property editor completing the RFC 6350 registries with structured-value fields, and a raw source editor.
- Added a merged contact view with UID-based deduplication, manual link and unlink, a merge flow, a semi-automatic duplicate remover, search, vCard import and export, and a divergence flag opening a conflict-resolution form.
- Added lossless round-trips for Google People and Microsoft Graph: vCard lines with no native slot are stashed server-side and restored verbatim, and provider-only fields ride the vCard as read-only vendor properties.
- Added a next-birthday peek computed from the merged cards.
- Added a Google Play support prompt with one-time pay-what-you-want tiers; the FOSS builds ship free and ungated.
- Added the packaging: a Nix flake pinning the toolchain and a release workflow assembling one signed APK per ABI plus a universal one.
- Set the minimum supported Android version to 8.0 (API 26).
