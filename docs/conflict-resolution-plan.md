# Conflict resolution plan: prompt, don't auto-merge

Today a same-field sync conflict is auto-resolved during sync, silently, local-wins. This plan makes it conservative: a genuine conflict (both sides edited the same field to different values) is left unresolved and surfaced to the user, who resolves it by hand in the conflict edit form the app already has (`editingConflict` mode: only the diverging fields show, each with a chip per candidate value, a warning marker, and the validate FAB gated until every field is reviewed). REV picks the default pre-filled value per field; both values stay available as chips, so nothing is ever lost silently.

## Where the policy lives (and why io-offline does not move)

io-offline is policy-agnostic. `reconcile_content` (io-offline `sync.rs`) only detects a both-sides-edited conflict: it marks the placement `Status::Conflict`, keeps `conflict_revision` on the latest remote, and returns without merging or pushing. An unresolved conflict survives every later sync (the `Status::Conflict` branch just refreshes `conflict_revision` when the remote moves again) and is cleared only when a `mutateEdit` stages a resolution. So "detect now, resolve later, by the user" is already the engine's contract; nothing in io-offline changes.

The resolution policy lives in the consumer. Today `OfflineEngine.resolveConflict` runs the vcard-rs three-way merge (local as `left`, so local wins collisions) immediately during `syncServer`, stages the result, and pushes it. This plan moves that decision out of sync and into a user gesture; the merge primitive stays, only its caller and timing change.

## Design

### The one new piece of persisted state: the remote body

The conflict form needs three documents: base (ancestor), local (staged edit), remote (server side). base (`base_vcard`) and local (`vcard`) already live on the card row; remote is currently fetched fresh inside `resolveConflict` and thrown away after the auto-merge. For offline manual resolution the remote body must be captured at conflict time (we are online and reading it anyway) and persisted, so the form opens without a round-trip.

Schema bump: `card.conflict_remote_vcard` (the remote body observed when the conflict was recorded, refreshed if the remote moves again while still unresolved). A row is "pending resolution" when `conflict_revision IS NOT NULL AND conflict_remote_vcard IS NOT NULL`.

### Genuine conflicts only

Not every field that differs between local and remote is a conflict. A field only one side changed is not a choice to make: the three-way merge already takes the changed side (and list fields, emails and phones, merge as sets and never collide). Only a field both sides changed, differently, is a real conflict that needs the user. The bridge computes this from the field models: project base, local and remote, and per managed scalar path flag a conflict when `local != base && remote != base && local != remote`. Lists and non-conflicting scalars are resolved by the vcard-rs merge into the base of the form; the flagged scalars become the form's `changed`/`alternatives`, exactly the shape `merge_cards` already feeds the conflict form.

### REV only picks the default

The default pre-filled value of a conflicted field is the newer side's, compared by REV; the other value rides as a chip. REV comparison is a vcard-rs concern (RFC 6350 timestamp grammar), so it is done there. Since REV only orders the default and both values remain choosable, an absent or unparseable REV is harmless: fall back to the local value as the default. (Local edits do not currently bump REV, so without the optional REV-stamp step below the remote is usually the newer side and thus the default; acceptable, and the user can pick local per field.)

## Stages

### Stage 0: vcard-rs REV comparison

Give `VcardTimestamp` a normalize-to-UTC (or comparison) that parses the RFC 6350 timestamp forms (basic and extended, `Z` and numeric offset, reduced precision) into a comparable instant. Unit-tested on the grammar variants, including offset equivalence (`...192559+0200` == `...172559Z`). No other vcard-rs change; `merge` already reports its `conflicts`, and the app derives the per-field candidates from the field models instead.

**Landed:** `VcardTimestamp::to_unix_seconds() -> Option<i64>` in vcard-rs `src/value/datetime.rs` (integer-only Howard-Hinnant civil-day conversion, `no_std`-safe; `None` for unparseable text; not exposed as `Ord` since instant-equality differs from the derived text `PartialEq`). Six unit tests (basic/extended equivalence, offset folding, ordering, missing-zone-as-UTC, reduced-precision padding, rejection); full lib suite (114) and clippy clean.

### Stage 1: capture the conflict instead of auto-resolving it

CardStore schema v17: add `conflict_remote_vcard`, plus a setter and its read in `loadConflicts`/the row loaders. In `OfflineEngine.syncServer`, replace the `resolveConflict` auto-merge with a capture: fetch the remote (as today), persist it as `conflict_remote_vcard`, leave the placement `Status::Conflict`, and do not stage or retry-push. Re-syncs of a still-unresolved conflict refresh the stored remote body and `conflict_revision`. The sync outcome reports pending conflicts distinctly from clean merges.

**Landed:** CardStore `VERSION = 17` + `card.conflict_remote_vcard TEXT` (the card table rebuilds on upgrade, so no ALTER) + `setConflictRemote(url, handle, remoteVcard)`. `OfflineEngine.syncServer` no longer auto-merges: `captureConflict` reads the remote and stores its body, leaving the row `Status::Conflict` (no stage, no push); the `resolveConflict` auto-merge and the temporary conflict logging are gone. `Report`/`SyncOutcome` gain a `conflicts` (pending) counter alongside `merged` (phone-axis auto-resolve, unchanged); `reportSync` shows `sync_conflicts_pending` ("N contact(s) diverged; resolve them to sync", EN+FR) when any pend. `conflict_remote_vcard` self-heals each sync (an engine `CONFLICT_REPLACE` write clears it, `captureConflict` re-sets it at pass end), and a resolution write clears it for free.

**Fix, part 1 (`captureConflict` etag override):** `captureConflict` originally also did `setConflictRevision(remote.etag)`, overriding the revision the engine recorded from the enumerate with this read's (GET) etag. On resolution the base adopts `conflict_revision` (io-offline `mutate.rs:156-167`), so the resolving push guards on it. Removed the override so the push guards on the engine's own enumerate etag. Necessary, but not sufficient for People (below).

**Fix, part 2 (People `updateContact` rejects the list etag):** even the enumerate etag failed with `HTTP 400: person.etag is different than the current person.etag`. Root cause: Google People etags are request-specific, and `updateContact` only accepts a `people.get` or prior-`update`-response etag, NOT a `connections.list` etag. The engine carries the list-delta etag as a pulled/conflicted row's revision, so the resolving push (and any first-edit-after-pull) sends a list etag that People rejects; normal edits work only because their etag chains from prior update responses. Fixed in `client.rs` `update_google_card`: on a 400 etag-mismatch, re-read the current etag via `people.get` and retry the update once (mirrors Google's "clear cache and get the latest person" guidance). Safe because io-offline's enumerate is the real concurrency guard: a genuine remote change since the base re-conflicts the row before the push runs, so the retry only bridges the etag-representation gap, it never silently clobbers an unseen remote edit. CardDAV unaffected (its list etag == read etag, and its If-Match retry quirk is separate).

Deviations / interim state (until Stages 2-3): the phone-axis conflict path (`resolvePhoneConflict`) still auto-merges; server conflicts now persist with no resolution UI yet, so a conflicted card stays unpushed and re-nags each sync. The only interim way to clear one is to open and save it in the normal editor (stages an edit, engine resolves against `conflict_revision`, local wins) — the proper both-values form arrives in Stage 3.

### Stage 2: the resolution bridge verb

`merge_conflict_form(base, local, remote)` in the cardamum rust bridge, returning the conflict form's shape `{vcard, model, alternatives, changed}`: the vcard-rs three-way merge (newer side as `left`, by the Stage 0 REV comparison) resolves lists and non-conflicting scalars into the merged body and supplies the defaults; the genuinely-conflicted scalar paths (model-level diff above) become `changed`/`alternatives` with both candidate values. One FFI plus its `Native`/`CardamumClient` wrappers, mirroring `mergeCards`.

**Landed:** `merge_conflict_form(base, local, remote)` in `project.rs`: picks the winner by `rev_seconds` (the new `REV` lens + Stage 0 `to_unix_seconds`, fallback local when either REV is missing), three-way merges with the winner as `left` (winner wins collisions = default, lists set-merge, remote-only scalar changes flow in), and diffs the base/winner/loser field models over `SINGLE_FIELDS` to emit `alternatives` (`[winner, loser]`) for genuine same-field collisions only; `changed` is always `[]` (non-null, so conflict mode still turns on). Empty `alternatives` = nothing needs the user. Rust FFI `Native.mergeConflictForm` + `CardamumClient.mergeConflictForm` (returns the JSON object). One unit test (ORG collides → default remote + both offered; remote-only TITLE flows in silently); full cardamum-rust suite (77) + fmt + clippy clean; Android app builds. (`rust/Cargo.toml` was briefly patched to a local vcard-rs path to build against Stage 0, then reverted to the git line once the change was pulled upstream.)

### Stage 3: surface and resolve in the app

- List: expose a `conflicted` flag per card (pending-resolution predicate above) through the contacts load onto `Entry`/`Group`; mark the row (the `ic_diverged` glyph, reused; distinguish from merged-view replica divergence if the two ever co-occur on one row). **Landed early (with Stage 2):** `CardStore.Indexed.conflicted` (from `conflict_revision` + `conflict_remote_vcard` both non-null in `loadIndexedCards`) → `Entry.conflicted` → `conflicted(Group)`; the adapter shows `ic_diverged` (dangerColor) when `conflicted(group) || diverged(group)`. Open + Resolve below still pending.
- Open: tapping a conflicted card opens the resolution form (not the normal editor). `openConflict(entry)` reads base/local/remote off the row, calls `merge_conflict_form`, and loads the existing `editingConflict` UI unchanged (warning markers, chips, gated FAB).
- Resolve: the form's save produces the resolved model; `applyCard` to the resolved vCard; `mutateEdit(url, handle, resolved)` stages it. Editing a conflicted placement clears the conflict in the engine (`conflict_revision` becomes the push base, already handled), so the next sync pushes the resolution guarded on the state it was resolved against. Clear `conflict_remote_vcard`.

**Landed:** `openGroup` routes a `conflicted(group)` tap to `openConflict`, which finds the first conflicted replica, `base.loadConflict(url, handle)` reads `{base, local, remote}` (base falls back to local when `base_vcard` is null; returns null when no remote captured yet → falls back to the plain editor), and `client.mergeConflictForm(...)` feeds the existing `editingConflict` form. An empty `alternatives` (only list edits collided) resolves straight away via `mutateEdit(merged)` with no prompt. Otherwise the editor opens with `resolvingConflict = true`, `editingVcard` = the merged source, one-replica `editingReplicas`, conflict mode on. `saveContact` gains an `else if (resolvingConflict)` branch → `saveConflictResolution`: `applyCard(editingVcard /* the merge */, model)` then `mutateEdit` onto the one replica, so remote's non-conflicting and unmanaged data survive; the engine resolution + `CONFLICT_REPLACE` clear `conflict_revision` and `conflict_remote_vcard` for free. `resolvingConflict` is reset in `openMerged`/`openNewContact`/`closeContact` (backing out leaves the conflict pending — conservative).

### Stage 4: edge cases and cleanup

- A conflicted card reached through the normal editor or the merged view routes to the resolution form instead.
- Multiple replicas with a sync conflict on one: resolve the per-replica sync conflict before the merged-view fan-out.
- Remove the temporary `cardamum-conflict` logging in `OfflineEngine.resolveConflict`.
- Upgrade: v16 rows have no pending conflicts (the old path auto-resolved them), so `conflict_remote_vcard` defaults null and nothing needs backfilling.

**Landed:** the list tap (the normal way into a card) routes conflicted cards to `openConflict` (Stage 3). `openConflict` picks the first conflicted replica, so a multi-replica group's per-replica conflict resolves before any merged-view fan-out; a second conflicted replica surfaces on the next tap/sync. The addressbook button hides while `resolvingConflict`. The `cardamum-conflict` logging was already removed in Stage 1 (its `resolveConflict` host is gone). No upgrade backfill needed (v17 rebuilds the card table). Known limitation: the duplicate-merge action still opens a conflicted card in the union-merge editor, where saving resolves local-wins; deliberate, rare, left as-is.

### Optional follow-up: REV-stamp on local edits

Independently, stamp `REV = now` on every locally-staged edit (choke point `OfflineEngine.mutateEdit`, via a pure `touchRev(vcard, isoNow)` FFI mirroring `setCardUid`; the content/divergence hash excludes REV, so no false divergence). This makes REV reflect the real local edit time, so the conflict-form default favours whichever side was actually edited last rather than always the server. Not required for correctness (both values stay choosable); purely better defaults.

### Stage 5: triage during the sync, not at tap time (landed 2026-07-13)

The empty-alternatives resolution moved from the tap (openConflict) into the sync pass itself: captureConflict became OfflineEngine.resolveCleanConflict, which captures the remote body as before, then runs merge_conflict_form right there; no genuine same-field collision means the merged card stages as the resolution on the spot (report.merged) and a second reconcile in the same pass pushes it, mirroring the phone axis' resolve-and-retry shape. Only genuine collisions stay conflicted and counted (report.conflicts), so a flagged card in the list is always one the resolution form has something to ask about; before this, a clean divergence sat flagged until tapped and then resolved silently, reading as a bug (icon gone, no form). The tap-time empty-alternatives branch stays as a fallback (conflicts captured by older builds) and now toasts what it did. Note the pass re-triages every pending conflict each sync: a genuine collision neutralized by a later edit on either side auto-resolves on the next pass.
