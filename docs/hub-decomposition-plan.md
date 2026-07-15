# Hub decomposition plan: splitting the oversized files

A handful of files concentrate too much responsibility: client.rs (3113), project.rs (2493) and ffi.rs (1959) on the Rust side, MainActivity.java (3190), ContactForm.java (1637) and OfflineStore.java (1194) on the Java side. This plan splits each along seams that already exist, one mechanical, behavior-preserving move at a time, every stage compiling both build variants and passing the Rust and JVM suites. Files that are already one cohesive concern are left alone: OfflineEngine, CardStore, ContactsList, and the MainActivity screen-registry and navigation spine.

## Rust

The three Rust hubs are pure or near-pure, so the splits are low risk.

### client.rs into client.rs + client/

The `Client<'a, 'local>` struct has only two fields (`env`, `transport`) and every backend method reaches I/O through the private `read(url)` / `write(url, bytes)` pair; the coroutine runners are generic over the coroutine type, not per backend. So the struct and its transport layer stay in client.rs, and each `impl Client` block moves to its own submodule, reached through `crate::client::...` (never `super`). `read`, `write` and the generic `run` / `run_redirect` become `pub(crate)` so the submodules can call them.

Target modules: client/discovery.rs (RFC 6764 discovery, the OAuth exchanges, the discovery walk, `run_discovery` / `run_mechanism`), client/dispatch.rs (the `Backend` router), client/carddav.rs, client/graph.rs (with the `GraphBatch` envelope structs), client/jmap.rs, client/google.rs (each holding its backend ops plus that backend's stateful sync round), and client/convert.rs (the free `to_card` / `to_addressbook` / `auth` / `normalize_vcard` / `coroutine_error` / `http_status` helpers). client.rs keeps the struct, `new`, `read` / `write`, the generic runners, and the module declarations.

### project.rs into project.rs + project/

project.rs is a flat set of pure vCard to field-model functions. Move the self-contained groups out: project/form.rs (the arrays.xml-coupled type tables and `form_view` / `form_entry` / `card_type_order` with their summary helpers), project/dedup.rs (the union-find duplicate detection), project/props.rs (the advanced raw-property editor), project/merge.rs (`merge_conflict` / `merge_conflict_form` / `merge_cards` and the list dedup), project/group.rs (`group_contacts` / `duplicate_group`). The core `project` / `apply` / `index` and the small shared helpers (`field` / `text` / `array` / `strings` / `types` / `fnv1a` / `escape_text`) stay in project.rs, exposed `pub(super)` to the submodules.

### ffi.rs into ffi.rs + ffi/

ffi.rs is roughly 90% mechanical arg-decode plus json-encode around a one-line delegate; the exported symbols are `#[no_mangle] extern "system"`, so their names are independent of module path and the split is free. Group the 55 exports into ffi/discovery.rs, ffi/oauth.rs, ffi/card.rs, ffi/offline.rs and ffi/project.rs; ffi.rs keeps the shared marshaling helpers (`read_string`, the `parse_*` family, `error_json`) and the module declarations. This split is the prerequisite for the JNI typing plan, which types the replies inside these per-domain modules.

## Java

### MainActivity: an EditorController is the biggest lever

The prior decomposition pulled out the screen contents (form, list, onboarding, oauth) but left the host wiring and the contact-write orchestration. The highest-value move is an `EditorController` owning the `edit` (EditSession) and `form` fields and absorbing three clusters: the open flows (`openGroup` / `openConflict` / `openMerged` / `openNewContact` / `addContact` and the merge helpers), the save fan-out (`saveContact` / `saveFanOut` / `saveConflictResolution` / `saveMerge` / `applyBookState`), and the advanced raw-vCard editor (`openAdvanced` and its ~440-line registry, param and value machinery, which already touches only `edit.advancedVcard` / `edit.advancedDirty` plus theme helpers). That removes roughly 1000 lines and the EditSession coupling, leaving MainActivity a navigation and lifecycle host.

Two lower-coupling extractions can go first as warm-ups: `AdvancedEditor` alone (self-contained), then a `ContactWriter` for the save fan-out (pure EditSession plus `form.collect()` to OfflineEngine, almost no View coupling). A `VcfTransfer` (the import and export file IO, leaving only the `onActivityResult` router on the activity) and an account-settings screen controller are optional follow-ups.

Left on the activity deliberately: the screen registry and flipper/overlay navigation (the dispatch spine), lifecycle and intent routing, permissions, edge-to-edge insets and theme delegations, the drawer/home panel, and the thin auth-flow forwarders.

### ContactForm: pull out the dialog layer

ContactForm mixes three concerns; two split cleanly. Extract a `ContactFieldDialogs` collaborator (the ~630 lines of per-property edit dialogs and their scaffolding: `displayNameDialog` / `phoneDialog` / `addressDialog` and the rest, plus `dialogContent` / `typeSpinner` / `autoCompleteField`) and the static field catalog and registry (the `Field` table, `catalog`, `offerable`, and the add-field chooser). The core `load` / `collect` / `render` and the diverged-review helpers stay as the form's heart.

### OfflineStore: optional server and phone split

Every OfflineStore method has a server variant and a `*Phone*` twin; the phone-spoke half (`loadPhoneCollection` / `applyPhoneUpsert` / `applyPhoneDrop` / `phoneHandlesBelowFull` / `loadPhoneConflicts` and friends, roughly 450 lines) can move to an `OfflinePhoneStore` sharing the same CardStore handle. Optional: the two halves interleave in the file but share almost nothing, so the split is clean if the file keeps growing.

Left alone: OfflineEngine (cohesive; its only latent seam is the per-backend push strategy, an extraction line only if it grows), CardStore (one DAO; the static key and handle helpers are a cosmetic `CardKeys` candidate at most), ContactsList (cohesive).

## Sequencing and verification

Rust first (lower risk): ffi.rs, then project.rs, then client.rs. Java next: `AdvancedEditor`, then `ContactWriter`, then the full `EditorController`, then `ContactFieldDialogs`, then optionally `VcfTransfer` and `OfflinePhoneStore`. Each move is independent and shippable on its own; run cargo fmt, clippy and test after every Rust stage, and gradle compileDebug and compileGoogle plus the :app and :client JVM suites after every Java stage. Nothing changes behavior, so the existing tests (82 Rust, the Robolectric engine tests) are the safety net.

## Risks

The client.rs split needs a few `read` / `write` / runner visibilities widened to `pub(crate)` and care with the `<'a, 'local>` lifetimes on the moved impl blocks. The `EditorController` is the one non-mechanical Java move: transferring ownership of `edit` and `form` plus the `show()` / `applyChrome` callbacks needs a host handle back to MainActivity, exactly as OnboardingFlow and OauthFlow already do. `ContactFieldDialogs` must keep the contacts-autocomplete and permission wiring.

## Landed

All stages complete and verified green (Rust: cargo fmt and clippy clean, 82 tests; Java: both variants compile, the :app and :client JVM suites pass). Every move was pure and behavior-preserving. The Rust hubs shrank from 3113/2493/1959 to 234/1412/86 lines and the Java hubs from 3190/1637/1194 to 1950/978/746.

- ffi.rs split into ffi.rs + ffi/ (2026-07-15): 1959 to 86 lines. The 55 no_mangle extern exports grouped by domain into ffi/discovery.rs (220), ffi/oauth.rs (314), ffi/card.rs (633), ffi/offline.rs (273), ffi/project.rs (482); ffi.rs keeps the header, the mod declarations, and the shared marshaling helpers (read_string, the parse_* family, error_json) as `pub(crate)`, each cluster's own helpers travelling with it.
- project.rs split into project.rs + project/ (2026-07-15): 2480 to 1412 lines. Groups moved to project/form.rs (267), project/dedup.rs (143), project/props.rs (276), project/merge.rs (333), project/group.rs (114), flattened back with `pub(crate) use <mod>::*;` so every external `project::X` path still resolves; the core project / apply / index and the shared low-level helpers stay in project.rs.
- client.rs split into client.rs + client/ (2026-07-15). Each `impl Client` block moved to its own submodule as an extra `impl<'a, 'local> Client<'a, 'local>` block reached through `crate::client::...`: client/discovery.rs (RFC 6764 discovery, OAuth exchanges, discovery walk, run_discovery / run_mechanism, plus oauth_post_request / oauth_error / search_domain), client/dispatch.rs (the Backend router plus sync_cards_round, parse_url, into_card_delta / href_resource), client/carddav.rs (the RFC 6352 verbs, run_sync_collection, auth, normalize_vcard, into_addressbook, into_card), client/graph.rs (the Graph verbs, run_msgraph / run_msgraph_delta, GraphBatch* structs, graph_card / parse_graph_url / graph_expand), client/jmap.rs (the JMAP verbs, jmap_session / run_jmap / run_jmap_changes, jmap_auth / jmap_addressbook), client/google.rs (the People verbs, run_google, google_card), and client/convert.rs (the genuinely shared coroutine_error / http_status / required / rejected). client.rs keeps the module header, the mod declarations, the `Client` struct + `new`, the transport upcalls `read` / `write`, the generic runners `run` / `run_redirect`, `clear_and_fail` (also used by offline.rs), the `USER_AGENT` constant (shared by carddav + discovery), and the `#[cfg(test)] mod tests`. Visibilities widened to `pub(crate)`: read / write / run / run_redirect (transport reached from every submodule), run_discovery + principal + addressbook_home_set (carddav's `.well-known` probe and discovery walk cross into carddav), push_graph_cards + push_jmap_cards (called from dispatch), every convert.rs helper, USER_AGENT, and normalize_vcard + search_domain (the kept tests reach them in their new modules). No behavior, signature or lifetime change. Deviations from the sketch: clear_and_fail stayed in client.rs (used only by read / write and offline.rs, never a backend, so it is not a convert.rs candidate); USER_AGENT stayed in client.rs as a shared transport constant rather than moving into a backend. Build clean (zero warnings), fmt clean, 82 tests pass.
- MainActivity.java extractions (2026-07-15): 3190 to 1950 lines, four collaborators on the OnboardingFlow host-handle idiom. AdvancedEditor.java (497, the advanced raw-vCard sub-editor), ContactWriter.java (259, the contact save fan-out), VcfTransfer.java (176, the import and export file IO, with the onActivityResult router kept on the activity), AccountSettings.java (398, the full-screen account-settings controller carrying its own working-state fields). The MainActivity members each collaborator reaches were widened to package-private; both variants compile.
- ContactForm.java split (2026-07-15): 1637 to 978 lines. The per-property dialog layer moved to ContactFieldDialogs.java (680) with a ContactForm host handle; the form's load / collect / render heart, the diverged-review helpers and the static field catalog stayed. Both variants compile.
- OfflineStore.java split into OfflineStore + OfflinePhoneStore (2026-07-15): 1186 to 746 lines. The phone-spoke half moved to OfflinePhoneStore.java (478) sharing the same CardStore; OfflineStore's server dispatchers route the phone collection to it, and the callers OfflineEngine and PhoneRemote construct an OfflinePhoneStore alongside their OfflineStore. Four read-only shared static helpers were widened to package-private rather than duplicated; both variants compile and the :app (30) and :client (3) JVM suites pass.

### Deferred (available follow-ups, not done)

- The full EditorController absorbing the contact open flows (openGroup / openConflict / openMerged / openNewContact and the merge helpers): the one non-mechanical Java move, edit-centric and view-coupled. AdvancedEditor and ContactWriter already carved out the advanced-editor and save-fan-out clusters separately, so the remaining lever is the open flows; left for a dedicated pass since it re-owns edit + form and needs the show() / applyChrome callbacks threaded carefully.
- ContactForm's static field catalog (the Field table, catalog, offerable, the add-field chooser): a ContactFieldCatalog candidate, but small enough that it stayed with the render heart this pass.
