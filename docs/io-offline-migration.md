# Migrating the sync base to io-offline

Cardamum's offline base (CardStore plus the hand-rolled sync loops in MainActivity) predates io-offline's mutable-content support and taught us what a contacts engine needs. This plan moves the replica mechanics onto io-offline, one shippable stage at a time, keeping the merged view and every screen untouched. Naming note: the crate stays io-offline for now; it is accurate, an engine for building offline-first apps.

Status: DONE, landed in one pass instead of the staged flag-guarded rollout (the pre-migration state is one commit back, which is the rollback path the legacy flag would have bought). See the Landed section at the bottom for what shipped and where the implementation deviates from the stages below.

## Why the fit is good now

io-offline models exactly what CardStore hand-rolls: a per-collection placement carrying a protocol `Handle`, a `Base { revision, object }` for the three-way merge (our etag plus base_vcard), a `Status` ladder (Clean, Dirty, Tombstone, Conflict, Created) matching our dirty/deleted/staged flags, and a content-addressed `Object` store. Its mutable-content axis (revision on `RemoteItem`, `Change::Update` with `if_match`, `conflict_revision`, update-beats-delete) landed in July 2026, which is what was missing when CardStore was built. Three mappings are almost free:

- `LinkId` is the vCard UID: the transparent-link merged view (docs/merged-view.md) reads placements grouped by link id natively, no extra layer.
- `Checkpoint` is the WebDAV sync-token (io-webdav's RFC 6578 read side is done), the JMAP state string, the Graph deltaLink, the People syncToken: the engine's delta enumerate upgrades every backend from today's full listings to incremental sync.
- The engine is an I/O-free coroutine with storage and remote as `Wants`: CardStore stays Java-side SQLite and services the storage yields over the same JNI upcall pattern the Transport already uses.

## What maps where

| CardStore / MainActivity today | io-offline |
| --- | --- |
| addressbook row | `Collection` (`CollectionId` = account email + book url) |
| card row (account_email, key, id, uri, etag, vcard) | `Placement` (handle) + `Object` (vcard bytes) |
| etag + base_vcard | `Base { revision, object }` |
| dirty / deleted flags, staged copies | `Status::Dirty` / `Tombstone` / `Created` |
| 412 handling + mergeCardChanges retry loop | `Status::Conflict` + `conflict_revision`, resolved by `Mutation::Edit` |
| membership table (m:n on JMAP/Google) | one placement per (book, card), same link id and object |
| membership MEMBER_ADDED / MEMBER_REMOVED | `Mutation::Copy` (with `Origin`, no body re-upload) / `Remove` |
| write-time index columns (name, email, phone, info, uid, hash) | `Meta` (opaque json summary, projected from the body) |
| full re-listing per sync | `WantsEnumerate` with `Checkpoint` cursor (delta when the server offers one) |

Stays consumer-side, untouched by the engine: the merged view and divergence display (the semantic model hash is a `Meta` field, distinct from the engine's byte hash), detached/link/dismissed_duplicate tables, subscriptions, the ContactsContract projection (syncLocal), the editor and all vCard computation (project/apply/merge stay as-is).

## Integration shape

Engine in the Rust bridge, seams in Java: a JNI entry drives `OfflineSync`/`OfflineMutate` step by step; each `OfflineYield` crosses the boundary as a JSON envelope the Java side services (storage yields against CardStore, remote yields against the existing backend clients), resuming with the matching `OfflineArg`. Yields are batched by design (one fetch yield carries many handles, one push yield many changes), so JNI chattiness stays proportional to sync phases, not to cards. Moving storage into Rust (rusqlite) is explicitly out of scope for this migration; it can be a later consolidation once the seams are stable.

## Stages

Each stage ships alone and keeps the legacy path behind a flag until parity is proven.

1. **Groundwork.** Inventory and funnel every CardStore write through a small set of methods (the screens only read). Add the blob/refcount view the engine expects: derive object refcounts from card rows first, a real table only if needed.
2. **Storage adapter.** Implement the storage seam over CardStore: card row to `Placement` (etag to `base.revision`, base_vcard to `base.object`), membership rows to per-book placements, `WriteOp` application (upsert/drop placement, store object, set base, set checkpoint) in one transaction per yield. Pure mapping, no behavior change; unit-test the round trip against fixture rows.
3. **Remote adapter, CardDAV first.** Service `WantsEnumerate` (RFC 6578 sync-collection with sync-token, falling back to the full PROPFIND when the server has none), `WantsFetch` (multiget), `WantsPush` (PUT If-Match, DELETE If-Match; the posteo unguarded-retry quirk lives here). Run the engine in shadow mode on one addressbook: log the WriteOps it would apply and diff them against what the legacy sync did.
4. **CardDAV cutover.** runRemoteSync's CardDAV path becomes open + sync on the engine. `Status::Conflict` routes to the existing conflict UI; resolving saves via `Mutation::Edit`, which conditions the push on `conflict_revision` (this replaces the inline 412 merge loop). Delete the legacy CardDAV push/pull code.
5. **Editor on the engine.** saveContact's fan-out becomes one `Mutation::Edit` per placement (the apply patch stays the content producer), delete becomes `Remove`, the addressbooks dialog becomes `Copy` (placeholder handle, `Origin` for account-level backends where an add is a membership patch) and `Remove`. The dirty/deleted columns retire in favor of `Status`.
6. **JMAP, Graph, Google adapters.** Same seams over the existing clients, with delta enumerates (state, deltaLink, syncToken). The m:n membership push maps `Add`-with-`Origin` to an addressbookIds/memberships patch instead of a copy; verify this adapter contract against io-offline and upstream a note if the semantics need naming.
7. **Cleanup.** Remove the legacy sync entirely, fold base_vcard into the object store, bump the schema (the addressbook table keeps surviving upgrades), and keep the index columns as the cached `Meta` projection.

## Risks and mitigations

- **Parity bugs against server quirks** (posteo no-internal-etag, fastmail well-known): the quirks live in the remote adapter, and stage 3's shadow mode diffs engine behavior against the legacy sync on real accounts before anything is applied.
- **m:n membership semantics**: the one-placement-per-book model multiplies rows for JMAP/Google; the adapter must dedup pushes per server object (one membership patch, not N). This is the one spot where io-offline may need an upstream refinement; flag it early in stage 2.
- **Store migration**: stage 2's mapping is read-compatible with the current schema, so no data loss; the destructive cleanup only happens in stage 7 after cutover.
- **First-sync cost**: unchanged (a full enumerate is what we do today); sync-tokens only make later syncs cheaper.

## Landed

What shipped, module by module:

- rust/src/offline.rs: the engine bridge. One JNI entry per verb (Native.offlineSync, offlineUpgrade, offlineMutate) drives the io-offline coroutine to completion and upcalls a Java OfflineDriver with one JSON envelope per yield (load, lookup, write, enumerate, fetch, push), mirroring the Transport upcall pattern. io-offline is a local path patch until its first release. Content hashes are opaque to Rust: Java computes SHA-256 of the vCard text on both seams.
- rust/src/client.rs plus ffi.rs: the CardDAV primitives the remote seam needed. Native.enumCards (addressbook-query, ETags only), Native.syncCards (RFC 6578 sync-collection, truncation drained in Rust, a rejected token surfaced as invalidToken for the fallback) and Native.multigetCards (addressbook-multiget body batches).
- CardStore (schema v14): the storage seam. loadCollection maps card and membership rows to placements (etag = base revision, base_vcard = base body, dirty/deleted/never-synced/conflict_revision = the status ladder, membership states = per-book created and tombstone placements), applyWrites applies one WantsWrite batch in one transaction (drops deferred to the batch end and cancelled by an upsert of the same placement, so a create's rekey never deletes what it renamed), and two new columns carry what the old schema could not: conflict_revision on card, checkpoint on addressbook (which survives upgrades via ALTER). A stale flag keeps a remotely-superseded body as a display copy until the refetch, instead of blanking rows mid-sync.
- OfflineEngine (app): the driver and the orchestration. Every backend enumerates incrementally from its stored cursor through one shared client verb (CardDAV RFC 6578 sync-collection, Graph contacts delta, JMAP ContactCard/changes, People sync tokens), an initial round listing the complete member set and still yielding the cursor to delta from; an expired cursor falls back to an initial round, and CardDAV keeps a plain-enumeration fallback for servers without sync-collection. The account-wide deltas of JMAP and Google are projected onto each book's enumerate (a changed card that left the book rides as vanished), their full changed cards double as the pass's fetch cache, and a complete Graph round primes its body cache with one listing (delta rows carry only id and changeKey, since delta cannot $expand the stash property). syncBook runs sync, hydrates the missing bodies (upgrade to Full), then auto-resolves conflicts exactly as the old 412 loop did: fetch the remote body, vcard-rs three-way merge (local wins), Mutation::Edit (which adopts the observed remote revision as the push base), and a second sync pushes the resolutions.
- MainActivity: runRemoteSync keeps the self-heal and the OAuth-refresh retry, but syncAccount is now three lines over OfflineEngine; the legacy fetch-push-refetch loops, the inline 412 merge block and the membership push (about 450 lines) are gone, along with the CardStore paths only they used (replace*, confirmPush, loadPending*, confirmMembership). Editor saves of existing replicas go through Mutation::Edit; creates, deletes and membership staging keep the CardStore funnel verbs the seam maps losslessly.

Deviations from the staged plan, and why:

- No shadow mode and no legacy flag (stages 3 and 4): parity diffing needs real accounts, and git already provides the rollback; the engine path landed as the only path.
- CollectionId is the addressbook URL alone, not account email plus URL: the URLs are globally unique already (absolute CardDAV URLs; the sentinel backends embed the account email), and the account is derivable from the addressbook table.
- The posteo If-Match quirk generalized: on 412 the push retries unguarded when the last enumerate proves the remote unchanged (listed with the staged ETag, or unlisted by a delta), and reports the change rejected otherwise so the next sync reconciles it.
- The editor is only partly on the engine (stage 5): content edits and conflict resolutions go through Mutation::Edit; deletes and membership toggles keep markDeleted and stageMembership, because a per-placement Remove cannot express the delete-the-card versus leave-this-book distinction the app owns (the seam maps both to the right placements anyway).
- Delta enumerates landed for all four backends (stage 6 complete): io-jmap (ContactCard/changes) and io-google-people (sync tokens) already had the wire primitives; io-msgraph gained a contacts delta coroutine (rows flagged @removed, deltaLink checkpoint, HTTP 410 as the expiry signal) and rides as a local path patch alongside io-offline until pushed.
- The engine's cross-collection body dedup (WantsLookupObject) is answered empty on purpose: it assumes a link id names immutable bytes, but contact replicas sharing a UID diverge legitimately, so a sibling's body must never stand in for a fetch. Worth an upstream io-offline note alongside the m:n membership dedup flagged above.
- A pending create with several memberships surfaces in its first membership's collection only (the m:n dedup risk flagged in the plan): the other books ride as created-with-origin placements once the row is synced, so one card never fans out into several server creates.

## Engine API bump (2026-07-09)

The engine was reshaped upstream; the bridge and store were migrated in one pass:

- Change::Add now carries link_id (idempotency key for at-least-once retried adds), flags (create with the right flag set; empty on contacts backends) and an object hash (the append fallback for a copy whose origin is gone); the wire forwards all three, the Java pushAdd keeps resolving the staged body by handle and may adopt the extras later.
- Base lost its present field (the base's existence is the membership base); WriteOp::SetBase and the count capability are gone from the engine, so the setBase wire op, applySetBase, applyPhoneSetBase and the count envelope were removed as dead code.
- A remote content change now drops the placement level to Probed instead of Meta; the store never round-trips the level (staleness is derived from the object hash), so nothing changed here, and the stale display copy keeps working as before.
- The sync report's pushed field now counts accepted pushes only (it previously counted attempts); rejected keeps its meaning.
- The push contract is explicitly at-least-once upstream now; the app already conformed (delete of a gone card converges as accepted, the phone spoke rewrites a raw contact already carrying the handle instead of duplicating it).
- The engine gained a rekey verb (rebuild after a handle-space change, carrying cache and pending state by link id); not wired into the app yet, a candidate for the CardDAV collection-URL-migration story.
