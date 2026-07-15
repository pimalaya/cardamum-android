# JNI typing plan: a smaller, typed bridge

The JNI boundary is 55 native methods, every one passing a JSON string in and a JSON string out; only 7 replies have a matching Java model class (ServiceConfig, AuthMethod, Addressbook, Card, CardDelta, OauthTokens, ServerMetadata). The other 48 hand a raw JSONObject or JSONArray back to the caller, who re-reads the keys with `.opt*()`. There are roughly 141 hand-built `json!` reply sites (project.rs 62, store.rs 52, ffi.rs 19, offline.rs 8), about 60 field-name string literals duplicated on both sides, and a scatter of stringly-typed dispatch (`accountBase` kind, `cardTypeOrder` and `formEntry` kind, the offline `action` result, `mutation.op` and `PushChange.op`). A typo in a key is a silent runtime miss, and that surface is the source of the runtime-only failure modes the bridge has already hit.

The reference already exists in the tree: offline.rs's driver-seam replies are fully typed serde structs and tagged enums (`PlacementJson`, `BaseJson`, `WriteOpJson`, `ChangeJson`, `MutationJson`, `StatusJson`). The plan makes store.rs and project.rs look like offline.rs: shrink the surface first, type it second, and only then consider generating the Java side.

## Lever C: narrow the surface (do first)

Independent of typing, fewer and coarser crossings mean less untyped blob to type later.

- Fold the five `accountInfo`-derived predicates (`isCarddav` / `isGraph` / `isGoogle` / `isAccountLevel` and the memoized map) into Account or base-URL construction, so the backend and account-level are decided once at account load instead of re-queried per call.
- Coarsen the eight pure offline-plan verbs (`offlinePushPlan` / `offlinePlacement` / `offlineUpsertPlan` and the rest): each is one row of facts to one decision, and store.rs reads that facts blob through 37 untyped `Value::get()` accessors, the single largest untyped hotspot. Push more of the per-row planning into the `offlineSync` engine drive so Java never assembles the facts blob, or merge the verbs into fewer coarser calls.
- Hold the field model typed across the editor: `projectCard` to `formView` to `formEntry` to `applyCard` currently shuttle the same untyped model JSON across JNI three or four times per edit; a single typed FieldModel DTO lets Java keep it typed between calls.

## Lever A: type the replies (do next)

Promote every `json!` reply in store.rs and project.rs to a named serde struct, mirroring offline.rs, and write the matching :client model class for each. Turn the stringly-typed dispatch keys into serde-renamed enums (the `accountBase` kind, the `cardTypeOrder` and `formEntry` kind, the offline `action`, the `op` fields). Make `syncCards` symmetric: serialize and parse CardDelta through serde on both sides instead of the current hand re-parse. Roughly 141 `json!` sites collapse to perhaps 30 to 40 structs, with about 30 new Java DTOs; the existing `object()` / `array()` error-unwrap helpers and the `BridgeError` to `CardamumException` contract stay as they are. This kills the runtime-key surface and the duplicated literals, at the cost of two hand-maintained copies until lever B.

## Lever B: generate the Java side (optional, later)

Once every method has a named Rust type (lever A removes the `serde_json::Value` returns that block any generator), evaluate generating the Java DTOs from the Rust serde types so drift becomes a compile error: either serde-generate and serde-reflection, which already target Java and speak serde, or schemars to emit JSON Schema plus jsonschema2pojo to emit the classes, wired into a build step. High upfront cost (toolchain and CI), low marginal cost per new method. The boundary is already all-JSON, so no transport change is needed.

## Interaction with the hub plan

Do the ffi.rs split from the hub decomposition plan first, so the typed replies land inside the per-domain ffi modules (ffi/card.rs, ffi/offline.rs, ffi/project.rs). Type store.rs and project.rs after their submodule splits. offline.rs needs nothing: it is the template.

## Verification

The behavior is pinned by the existing suites: the 82 Rust tests and the Robolectric engine tests that run the real store against the host-built bridge. Every stage keeps them green. As typed structs appear, add a round-trip check per struct (build in Rust, parse in the Java DTO) where practical, so the two hand-maintained copies are tested against each other until lever B removes the duplication.

## Landed

(nothing yet)
