# Cardamum for Android: design

The core idea and the target structure of the app. The current code implements the connection flow and the direct-CRUD contacts screen; the sync engine and the phone projection are specified here and land incrementally.

## Screens

One activity, one ViewFlipper, one panel per screen:

1. **Connection** (email, config, password panels). Shown on first launch and, later, when configuring a new account. The user enters an email; Cardamum detects the provider family from the domain and proposes matching configurations:
   - Google account: Google Contacts API (io-google-people) or Google CardDAV over OAuth. Both pending; password CardDAV is not proposed because Google does not accept it.
   - Microsoft account: Microsoft Graph (io-msgraph contacts) over OAuth. Wired end to end behind the same operations as CardDAV (the account's msgraph:// base URL routes them); the Rust bridge projects Graph contacts to and from the vCard document of record, since Graph has no vCard representation. Waits only on the Entra app registration client id.
   - Anyone else: standard CardDAV, resolved via pimconf RFC 6764 discovery (SRV, TXT, .well-known over a DNS-over-TCP resolver), plus JMAP for Contacts (RFC 9610) once io-jmap grows contacts support.

   Picking CardDAV asks for the password, verifies the connection (the addressbook discovery walk doubles as the check) and stores the account AES-GCM-encrypted under an Android Keystore key.

   Straight after a successful connection comes the **addressbook selection** step: the discovered addressbooks are listed with checkboxes and the user picks which ones to sync locally. Each selected addressbook becomes one Android account of Cardamum's own account type (the DAVx5 pattern; Android has accounts, not addressbooks), created via AccountManager with an authenticator stub. Per-account raw contacts carry the vCard UID and ETag in their sync columns, deleting the account cleanly removes its projected contacts, and the user can toggle visibility per addressbook in any contacts app.

2. **Sync**. Two manual actions behind the Sync menu of the contacts screen, matching the two spokes (running both from one action comes later):
   - *Sync remote* (store to server, the only moment the app touches the network): per addressbook it fetches into the store (so staged rows learn the server's resource names), pushes the staged changes (creates with If-None-Match, updates and deletes with If-Match; a 412 means the remote changed underneath, the change stays staged and is counted in a conflict toast), then re-fetches the pushed state. When the remote is unreachable the screens fall back to the store of the last sync.
   - *Sync local* (store to phone, no network): reconciles the per-addressbook Android accounts and requests one manual expedited sync per account from Android's sync scheduler; the projection itself runs in the registered sync adapter (unchanged content hashes skipped, contacts of other accounts never touched), which is the same path as the system's per-account "sync now". Automatic sync stays off. Asks for the contacts permission on first use, and phone-side edits are not ingested yet; the real engine is below.

   Launch is offline first: the contacts screen renders instantly from the store; the app never syncs by itself.

3. **Contacts** (list + editor panels). Lists the cards of every synced addressbook by FN. The editor is a tabbed form (name, contact, address, other, plus a read-only source tab showing the raw vCard) over the same neutral field model the phone projection uses: the form collects the model and the Rust bridge patches it onto the stored vCard through the vcard-rs CST, so every property the form does not manage (PHOTO, CATEGORIES, IMPP, X-*) survives untouched. Saving and deleting are offline first: they only stage the change in the base; the next sync pushes it.

## Sync engine (planned)

Hub and spoke. The vCard store is the hub replica: the document set the app actually edits, full vCard fidelity, offline by construction (SQLite today, a vdir or io-m2dir-style backend tomorrow; the spokes do not care). The phone contacts and the remote are two spokes, and a Sync pass reconciles the hub with each spoke pairwise, reusing io-offline (name will change; full enumeration only, no partial) twice with two backends:

- **store to remote**: full fidelity, merged in vCard space with the vcard-rs three-way merge; per-card identity is the ETag plus the base vCard. This is io-offline with the CardDAV backend (or a provider API later).
- **store to phone**: lossy, merged in field-model space; the phone is just another io-offline remote whose items are raw-contact rows, whose content token is the field-model hash, and whose reads and writes go through the Java converter. Cardamum owns one raw-contact account per selected addressbook (the DAVx5 pattern) and keeps the vCard UID and ETag in the raw contact's sync columns.

Two pairwise syncs beat one tri-directional merge: each merge stays in a single representation space (whole vCards on one spoke, lossy field models on the other), and the spokes fail independently (no network still syncs the phone exactly; no contacts permission still syncs the remote).

Within one Sync pass the phone spoke is local-only and cheap, so it runs twice: ingest phone edits into the store, reconcile the store with the remote, then project the pulled changes back to the phone. One user action, and a phone edit reaches the server in the same pass.

Inside the store, what the user edits is the working copy; each card also keeps the base of the last reconciliation (base vCard plus ETag), which user edits never overwrite: the working-vs-base diff is the app's own change, the phone-vs-projected-base diff is the phone's, the fetched-vs-base diff is the remote's. One base per card serves both spokes as long as a pass always runs both. (The current code still overwrites the row on edit and keeps only the ETag as base marker, which is sound only while phone edits are not ingested; the base/working column split lands with the engine.)

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
- Phone-side edit detection and pushing (the io-offline three-way merge); projection is one-way, remote wins.
- Push or periodic background sync; every sync is user-triggered (the registered sync adapter is a stub the engine hooks into later).
- The JMAP backend; the config screen already reserves its slot. OAuth accounts persist their refresh token and refresh expired access tokens transparently on sync (a 401 triggers one refresh-and-retry per addressbook).
