# Cardamum for Android: design

The core idea and the target structure of the app. The current code implements the connection flow, the contacts screen and the hub-and-spoke sync engine on io-offline, both spokes included (docs/io-offline-migration.md for the server spoke, docs/phone-sync-plan.md for the phone one).

## Screens

One activity, one ViewFlipper, one panel per screen:

1. **Connection** (email, config, password panels). Shown on first launch and, later, when configuring a new account. The user enters an email; Cardamum detects the provider family from the domain and proposes matching configurations:
   - Google account: Google Contacts API (io-google-people) or Google CardDAV over OAuth. Both pending; password CardDAV is not proposed because Google does not accept it.
   - Microsoft account: Microsoft Graph (io-msgraph contacts) over OAuth. Wired end to end behind the same operations as CardDAV (the account's msgraph:// base URL routes them); the Rust bridge projects Graph contacts to and from the vCard document of record, since Graph has no vCard representation. Waits only on the Entra app registration client id.
   - Anyone else: standard CardDAV, resolved via pimconf RFC 6764 discovery (SRV, TXT, .well-known over a DNS-over-TCP resolver), plus JMAP for Contacts (RFC 9610) once io-jmap grows contacts support.

   Picking CardDAV asks for the password, verifies the connection (the addressbook discovery walk doubles as the check) and stores the account AES-GCM-encrypted under an Android Keystore key.

   Straight after a successful connection comes the **addressbook selection** step: the discovered addressbooks are listed with checkboxes and the user picks which ones to sync locally. Each selected addressbook becomes one Android account of Cardamum's own account type (the DAVx5 pattern; Android has accounts, not addressbooks), created via AccountManager with an authenticator stub. Per-account raw contacts carry the vCard UID and ETag in their sync columns, deleting the account cleanly removes its projected contacts, and the user can toggle visibility per addressbook in any contacts app.

2. **Sync**. Two manual actions matching the two spokes; the in-app Sync (the drawer row, or pulling down on the contacts list) runs both in one pass, while Sync local remains as a headless hook:
   - *Sync remote* (store to server, the only moment the app touches the network): per addressbook, the io-offline engine reconciles the store with the remote (docs/io-offline-migration.md): it enumerates the member spine incrementally from the stored checkpoint (RFC 6578 sync-collection, Graph delta, JMAP /changes, People sync tokens; a complete round when the cursor is missing or expired), three-way merges each placement against its base, pushes the local-won changes (creates, If-Match-guarded updates and deletes, membership patches), batch-fetches the bodies the spine misses, and resolves conflicts by merging both sides against the staged base (the local side wins same-field collisions, an update beats a removal) before a second reconcile pushes the resolutions. When the remote is unreachable the screens fall back to the store of the last sync.
   - *Sync local* (store to phone, no network): reconciles the per-addressbook Android accounts and runs the two-way phone engine pass per subscribed book in-process, behind the same modal loader as the remote sync. The registered sync adapter runs the identical pass for the syncs the OS schedules on its own (the system's per-account "sync now", and the upload syncs Android requests after a contacts-app edit). Asks for the contacts permission on first use; until it ran once (no Android accounts yet), the remote sync's own phone passes stay silently off.

   *Sync remote* also runs the phone spoke: each book syncs phone, then server, then phone again, so a contacts-app edit reaches the server in one pass and the server round projects back in the same one.

   Launch is offline first: the contacts screen renders instantly from the store. By default the app never syncs by itself; each addressbook can opt into scheduled background sync (the "Synchronize in background" cadence, set at the end of the connection flow or from the account's settings screen, account-wide or per book behind its Advanced fold), which runs the same three-pass book sync through one WorkManager periodic worker per book, the DAVx5 pattern; a book whose remote switch is off keeps its phone pass and skips the server exchange. A pass that did something posts a notification shaped like the in-app sync toast (pulled, pushed, merged, titled by the book); a pass with nothing to report posts nothing. Contacts in a pending both-sides-edited conflict sit each pass out (the engine parks them untouched, per item, while everything else keeps syncing) and ride the notification as a warning subtitle on every pass until the user resolves them in the app; enabling a cadence also turns on the Android account's content-triggered sync, so contacts-app edits upload into the hub as they happen.

3. **Contacts** (list + editor panels). Lists the cards of every synced addressbook by FN. The editor is a tabbed form (name, contact, address, other, plus a read-only source tab showing the raw vCard) over the same neutral field model the phone projection uses: the form collects the model and the Rust bridge patches it onto the stored vCard through the vcard-rs CST, so every property the form does not manage (PHOTO, CATEGORIES, IMPP, X-*) survives untouched. Saving and deleting are offline first: they only stage the change in the base; the next sync pushes it.

## Sync engine

Hub and spoke. The vCard store is the hub replica: the document set the app actually edits, full vCard fidelity, offline by construction (SQLite today, a vdir or io-m2dir-style backend tomorrow; the spokes do not care). The phone contacts and the remote are two spokes, and a Sync pass reconciles the hub with each spoke pairwise, reusing io-offline twice with two backends:

- **store to remote**: full fidelity, merged in vCard space with the vcard-rs three-way merge; per-card identity is the ETag plus the base vCard. This is io-offline with the CardDAV backend (or a provider API later). DONE: this spoke runs on the engine today, see docs/io-offline-migration.md.
- **store to phone**: lossy at the boundary, lossless in the hub; the phone is just another io-offline remote whose items are raw-contact rows, whose content revision is the raw contact VERSION, and whose reads and writes go through the Java converter (Mapping both ways, the read-back applied onto the last converged vCard as a field-space patch). Cardamum owns one raw-contact account per selected addressbook (the DAVx5 pattern) and keeps the card id in SOURCE_ID and the vCard UID in SYNC2. DONE: this spoke runs on the engine too, see docs/phone-sync-plan.md.

Two pairwise syncs beat one tri-directional merge: each merge stays in a single representation space (whole vCards on one spoke, lossy field models on the other), and the spokes fail independently (no network still syncs the phone exactly; no contacts permission still syncs the remote).

Within one Sync pass the phone spoke is local-only and cheap, so it runs twice: ingest phone edits into the store, reconcile the store with the remote, then project the pulled changes back to the phone. One user action, and a phone edit reaches the server in the same pass.

Inside the store, what the user edits is the working copy; each card also keeps one base per spoke, which user edits never overwrite: the working-vs-base diff is the hub's own change on that spoke, the fetched-vs-base diff is the spoke's. The vcard column is the working copy; base_vcard plus etag is the server base on the card row, phone_base plus phone_revision the phone base on the membership row (per book, since each book projects its own raw contact), and the engine's placements are mapped over both axes.

The field-by-field mapping between ContactsContract data kinds and vCard properties, the split of the converters between Java and Rust, and the rules that prevent infinite edit loops (CALLER_IS_SYNCADAPTER, patch-never-regenerate through the vcard-rs CST, field-space diffing) live in [docs/contacts-mapping.md](./contacts-mapping.md).

### First sync

The first sync has no base and the phone may hold contacts that do not exist remotely, so a blind three-way merge cannot dedupe a person present on both sides with two non-identical vCards. Plan:

1. First sync is remote to store to phone only: remote is right, the store is empty, and existing phone contacts are left completely untouched (they live in other raw-contact accounts anyway).
2. Importing pre-existing phone contacts is a separate, explicit, interactive step, offered after the first sync: exact matches on email or phone number are merged silently, ambiguous candidates are reviewed one by one (keep both, merge, skip), and unmatched locals are proposed as new remote contacts.

This keeps the first sync fast and deterministic and confines the slow interactive part to an optional screen, instead of blocking onboarding on a review of the whole phone book.

### Conflicts

Divergent edits on the same contact keep both sides (no silent loss), following the Pimalaya-wide rule; field-level merging becomes possible once the vcard-rs diff lands.

## Out of scope for now

- Multiple accounts (logins); multiple addressbooks per account are in.
- Push sync (WebDAV-Push over UnifiedPush); periodic background sync is in, per addressbook, through WorkManager.
- The JMAP backend; the config screen already reserves its slot. OAuth accounts persist their refresh token and refresh expired access tokens transparently on sync (a 401 triggers one refresh-and-retry per addressbook).
