# Provider test log

The living record of real-provider testing: one section per provider, updated after each test round. Status values: untested, partial (some flows exercised), OK (onboarding, sync round-trip and editing verified), broken (with the failing flow). Anything not written down here is untested, whatever the code claims.

Cross-provider notes:

- Every backend enumerates incrementally since the io-offline migration (docs/io-offline-migration.md): the delta path (sync-collection, Graph delta, JMAP /changes, People sync tokens) and its expired-cursor fallback are exactly the code paths a test round should exercise (sync twice: the second pass must be a cheap delta, then edit remotely and sync again).
- The configuration list only offers drivable variants: password, Bearer token, and OAuth code grants (custom client, or the Cardamum client for Google and Microsoft once registered).

## posteo (CardDAV, SabreDAV)

- Status: untested since the io-offline migration (was the main test account before it).
- Auth: password (Basic).
- Known quirk: some resources carry no internal ETag, so their If-Match always fails even though the listing serves one; the push retries unguarded when the last enumerate proves the remote unchanged (OfflineEngine.listingUnchanged).
- To verify: RFC 6578 sync-collection support (SabreDAV has it; the sync-token delta path is new), and the quirk retry still firing on the engine path.

## fastmail (CardDAV + JMAP)

- Status: partial (2026-07-08, discovery step only).
- Auth: password/app password on CardDAV; JMAP is Bearer-token only server-side. Its discovered OAuth method is not drivable by the app and is no longer listed.
- Known quirk: the server 404s every request outside /dav/*, so a bare origin probes .well-known/carddav for the real context root first (client.rs list_addressbooks).
- Finding (discovery, FIXED in pimconf 2026-07-08): the config list showed two CardDAV entries, carddav.fastmail.com and a dNNNNNN.carddav.fastmail.com shard. Root cause (reproduced with the pimconf CLI): fastmail's PACC document advertises the canonical host while its SRV records answer with a rotated shard (a different one per resolve), and the collector only merged byte-identical URLs. The search now merges a subdomain endpoint into its parent host, and compares HTTP endpoints as normalized URLs; one canonical entry remains.
- Finding (discovery, FIXED in pimconf 2026-07-08): the "JMAP, password" variant came from fastmail's own PACC document, whose account-level authentication block claims password support (true for CardDAV app passwords, not for JMAP). Per PACC §5.4.2, pimconf's search now probes every collected HTTP endpoint's unauthenticated 401 and refines each config from its WWW-Authenticate schemes: fastmail's session endpoint advertises Bearer only (plus an RFC 9728 resource_metadata pointer), its CardDAV root Basic and Bearer, so the JMAP config lost its password variant and the CardDAV one gained the API-token variant. Verified live with the pimconf CLI.
- Finding (OAuth, verified live 2026-07-08): fastmail implements the full draft-ietf-mailmaint-oauth-public chain: RFC 9728 resource metadata behind the 401's resource_metadata pointer, RFC 8414 server metadata with an RFC 7591 registration_endpoint (public clients, S256 PKCE, mail/contacts/calendars scopes).
- Finding (OAuth redirect, verified live 2026-07-08): fastmail's RFC 7591 registration REJECTS every http redirect, loopback included (`http://127.0.0.1:PORT`, `http://localhost:PORT`, even `https://127.0.0.1`), AND a bare dot-less private-use scheme (`cardamum://...`): all "invalid_redirect_uri redirect_uri not valid scheme". It accepts only a reverse-DNS private-use scheme (`org.pimalaya.cardamum://oauth2redirect` registers fine), per the RFC 8252 §7.1 "reverse domain name" recommendation. Stalwart accepts loopback or a private-use scheme. So the app registers and redirects with `org.pimalaya.cardamum://oauth2redirect` (its own manifest intent-filter added next to the older `cardamum` one), OS-routed to onNewIntent; the loopback server stays only for the manual custom-client prompt.
- Finding (OAuth scope, 2026-07-08): requesting the metadata's whole `scopes_supported` set fails authorization with "invalid scope" (fastmail advertises mail + contacts + calendars + an MCP scope + offline_access; registration echoes any scope without validating, so the rejection lands at authorize). `scopes_supported` is what the server SUPPORTS, not what the app should REQUEST. The app now requests only the standard contacts scope `urn:ietf:params:oauth:scope:contacts` plus `offline_access` (for the refresh token), intersected with what the server advertised (ServerMetadata.contactsScope); a server advertising no scopes gets the standard pair as-is.
- WIRED (2026-07-08): the app drives zero-registration OAuth end to end. When a config carries only an issuer (pimconf OauthIssuer, from PACC oauth-public), picking it fetches the 8414 metadata, registers a public client via 7591 (reverse-DNS custom-scheme redirect, no secret), then runs the code grant with PKCE over the OS-routed redirect (like the built-in Google/Microsoft flows), persisting the account with the issued client id so refresh works. No registration_endpoint (Google, Microsoft) falls back to the custom-client prompt with endpoints prefilled. Registration verified live against fastmail + Stalwart with curl; the in-app browser round-trip is still UNTESTED on device.
- To verify: CardDAV sync-collection deltas; the JMAP Contacts path end to end (RFC 9610 ContactCard, /changes deltas, m:n addressbook memberships) over an API token.

## Google (People API or CardDAV, OAuth)

- Status: untested; waits on the OAuth client registration (BYO client works today via the custom-client prompt).
- Auth: OAuth code grant with PKCE; contacts scope is "sensitive" (free verification).
- Known behaviors: creates land in the myContacts system group (the engine patches the target group right after); memberships are m:n contact groups; updates guarded by the person etag; sync tokens bind to their field mask (the delta round always asks READ_FIELDS plus metadata).
- To verify: sync-token deltas incl. the EXPIRED_SYNC_TOKEN fallback; deleted persons riding flagged in delta responses.

## Microsoft (Graph, OAuth)

- Status: untested; waits on the Entra app registration client id (BYO client works today).
- Auth: OAuth code grant; Graph is bearer-only, no app passwords.
- Known behaviors: no If-Match on updates (last-write-wins); the server assigns resource ids; delta rounds carry id and changeKey only (no $expand of the stash extended property in delta), bodies fetched per card; an expired deltaLink answers HTTP 410 and falls back to an initial round.
- To verify: the whole contacts flow (never tested against a live tenant), delta rounds included.
