# Phone sync plan: ContactsContract as a second io-offline spoke

The store is the hub with two spokes: the remote server (CardDAV, Graph, JMAP, People) and the phone's ContactsContract. Today only the server spoke is a real sync (io-offline engine); the phone spoke is a one-way projection, so edits made in the phone's contacts app on Cardamum's raw contacts (which set the provider's DIRTY flag, bump VERSION, and set DELETED on removal) are never read back and get clobbered by the next projection. This plan makes the phone a second io-offline spoke: same engine, same Rust semantics, Java limited to interfacing ContactsContract.

## Design

io-offline is I/O-free: storage and remote are driver yields (load, lookup, write, enumerate, fetch, push). Nothing in the engine assumes a network remote, so the phone spoke is one more remote adapter behind the same OfflineEngine.serve seam. Everything the harvest-style alternative would have re-implemented in Java (update-beats-delete, creates, conflict bases, revision-guarded pushes, no-op detection) stays in the engine, identical for both spokes.

One logical card, one store row, two spoke axes. A placement is per collection in the engine, so the phone spoke syncs a sibling collection id (the addressbook URL with a phone marker) that the driver maps onto the same card rows, exposing a second state axis: the server axis keeps etag and base_vcard as today, the phone axis adds phone_revision (the raw contact VERSION at last convergence) and phone_base (the vCard last projected). Cross-spoke propagation then falls out of the hub row: a phone edit merged into the hub makes the hub diverge from the server base, so the next server sync pushes it, and vice versa; a phone-created card lands as a hub row with no etag, which the server sync creates upstream.

Lossiness is handled at the fetch boundary. The phone can only represent the EditSchema fields, so the phone "body" handed to the engine is never a bare reverse-mapped vCard: fetch reverse-maps the data rows to the neutral field model and applies it onto the phone base with the existing applyCard verb (field-level patch, unknown vCard properties survive). The engine then merges full vCards on both spokes with the same vcard-rs union merge.

Phone handles are the card id (already stamped in SOURCE_ID); server handles remain the server resource names. One raw contact is one card, so a phone edit is a per-card (per-replica) edit; the merged-view fan-out stays an in-app editor feature.

## Stages

### Stage 0: seam audit and Mapping inverse

Re-read the io-offline driver contract (pimdir/SPEC.md and the current OfflineEngine.serve arguments) against the phone axis: exactly which placement fields loadCollection must expose for the phone collection, how staged mutations scope per collection (an in-app edit stages on the server collection; the phone spoke must still see the hub change, via content-vs-phone_base divergence, not via the mutation), and whether the engine's dedup assumptions (lookup answered empty today) need the same treatment on the phone collection.

In parallel, add the pure rows-to-model direction next to the existing model-to-rows in Mapping, covering the same eight data kinds plus the type mappings back to vCard TYPE sets. JVM-tested like the forward direction: round-trip identity over the MappingTest fixtures, plus phone-authored row shapes (display-name-only names, bare numbers, custom labels).

### Stage 1: the phone axis in CardStore

Schema v15: card gains phone_revision and phone_base (per-spoke placement state; no checkpoint column, the phone enumerates complete rounds since reading ContactsContract is local and cheap). CardStore.loadCollection learns the phone collection id and answers from the same rows through the phone axis; applyWrites learns to update it.

### Stage 2: the phone remote adapter

The ContactsContract half of the driver, next to the server backends in OfflineEngine:

- enumerate: walk the account's raw contacts; each row yields (handle = SOURCE_ID, revision = VERSION), DELETED=1 rows ride as vanished, rows without SOURCE_ID (created in the phone's contacts app) yield fresh handles. Always a complete round.
- fetch: read the raw contact's data rows, reverse-map to the field model, return applyCard(phone_base, model) as the body.
- push: project the hub vCard as data rows (the existing Projector write path, folded in here), stamp SOURCE_ID and the SYNC columns, record the resulting VERSION as phone_revision and the projected vCard as phone_base. Deletions purge the raw contact. All writes CALLER_IS_SYNCADAPTER, guarded on the recorded VERSION so a contacts-app edit racing the sync is left dirty for the next round.

### Stage 3: orchestration

syncBook becomes three engine passes: phone (pull the contacts app's edits into the hub), server (exchange with the remote), phone again (project what the server round brought; local-only, cheap). "Sync local" runs the phone pass alone; SyncService.onPerformSync runs it too, so the upload sync Android requests when someone edits our raw contacts converges without opening the app. The standalone Projector projection retires; automatic periodic sync stays off.

### Stage 4: shakeout matrix

On-device, per backend family: edit a field in the phone app and see it in Cardamum and on the server; delete and create from the phone app; edit the same card in the phone app and remotely between syncs (engine three-way merge, update beats delete both ways); dirty-but-unchanged raw contacts converge silently; a field only Cardamum knows survives a phone-side edit of another field.

## Landed (2026-07-09)

All stages are in, with these deviations from the plan text above, each forced by the seam audit:

- **The phone axis lives on the membership table, not the card table.** On the account-level backends one card sits in several books, each book is its own Android account with its own raw contact and an independent VERSION, so per-card columns would ping-pong between books. The axis is per (book, card), which is exactly the membership row.
- **Four columns, not two.** The engine's pull-content path drops the body and expects a per-axis refetch marker, and conflicts persist their observed revision across passes, so the axis mirrors the server one in full: phone_revision, phone_base, phone_stale, phone_conflict_revision (schema v15; the card tables rebuild, the addressbook table survives).
- **The fetch boundary overlays instead of replacing.** applyCard deletes managed properties absent from the model, so a bare reverse-mapped model would strip everything the phone cannot represent. The body is applyCard(phone_base, Mapping.merge(project(phone_base), Mapping.model(rows))): merge takes a field from the phone only when it differs from what phone_base itself projects onto the phone, so mapping lossiness (the FN-diverged name row, canonicalized TEL types) never reads as an edit and Cardamum-only fields ride along.
- **The adapter is its own class.** PhoneRemote replaces Projector (deleted) next to OfflineEngine, which routes phone-collection yields to it; Mapping stays the pure JVM-tested half, now in both directions plus merge.
- **Enumerate heals as it walks.** DELETED raw contacts are purged (absence from the complete round is the vanished signal), contacts without SOURCE_ID get stamped a fresh handle before being listed (a crash never ingests them twice), and a SOURCE_ID the store no longer holds is a stale projection and is purged too; that last rule is what makes the v15 rebuild converge in one run instead of resurrecting pre-spoke projections as creates.
- **No Rust changes.** The bridge and the engine are collection-agnostic; the whole spoke is Java against the existing serve seam.
- The sync adapter now declares supportsUploading, so a contacts-app edit makes Android schedule the phone pass by itself; syncBook runs phone, server, phone, and skips the phone passes silently until "Sync local" created the Android accounts and the contacts permission is granted. In-app actions never detour through the sync scheduler (its queuing latency reads as lag): "Sync local" runs the phone passes in-process behind the sync spinner, and SyncService serves only the OS-scheduled syncs.

## Limits, accepted up front

- Only fields the EditSchema exposes round-trip; everything else is Cardamum-only and preserved by the applyCard patch at the fetch boundary.
- Group membership is out of scope: the projector removes groups today, and per-placement membership moves have their own engine semantics (see the io-offline migration notes).
- The photo kind is declared in the EditSchema but not projected; photos stay out of scope for this pass.
