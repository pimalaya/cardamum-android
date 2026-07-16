# The JNI boundary: the untyped-wire problem

The detailed problem analysis behind [jni-typing-plan.md](jni-typing-plan.md): what the JNI boundary is, why its current shape is a liability, and how the three-lever plan addresses it. Read this to understand the why; read the plan for the staged how.

## How the bridge works today

The architecture is: the Rust core is I/O-free (coroutines that emit read and write requests), Java owns the sockets and TLS, and the bridge between them is stateless. Java hands Rust a bag of facts, Rust hands back a plan or a result; there are no Rust-held sessions.

The detail that matters here is how that bag of facts and that result are encoded: everything crosses the boundary as a JSON string. Of the 55 native methods in Native.java, every one takes JSON strings (plus the odd int/boolean and the two opaque Transport and OfflineDriver handles) and returns a JSON string. A call is this round trip:

1. Java hand-builds an argument blob with org.json and serializes it to a string.
2. Rust decodes it with `serde_json::from_str::<Value>` and reaches into it field by field with `facts.get("...").and_then(Value::as_str)`. store.rs alone has 37 such hand-written `Value::get` accessors.
3. Rust does the work and hand-builds the reply with the `json!` macro. There are about 141 hand-built `json!` reply sites across the crate (project.rs 62, store.rs 52, ffi.rs 19, offline.rs 8).
4. Java re-parses that reply by hand with `.optString(...)` / `.optBoolean(...)` / `.optJSONArray(...)` and branches on the values.

Illustrative (simplified) round trip for one plan verb:

```java
// Java: hand-build the facts, call, hand-parse the reply
String facts = new JSONObject().put("ref", ref).put("books", books).toString();
JSONObject plan = new JSONObject(Native.offlinePushPlan(facts));
switch (plan.optString("action")) {
    case "membership": ...; case "delete": ...; // else: silently nothing
}
```

```rust
// Rust: decode field by field, hand-build the reply
let facts: Value = from_str(&facts)?;
let r = facts.get("ref").and_then(Value::as_str);
// ...
json!({ "action": "membership", "postCreateBooks": books })
```

Only 7 of the 55 replies get a real Java class (Card, Addressbook, CardDelta, OauthTokens, ServiceConfig, AuthMethod, ServerMetadata); the other 48 hand a raw JSONObject or JSONArray straight back to the caller, who pokes at the keys.

## The problem

The contract between the two languages (what fields exist, how they are named, what shape they take) lives nowhere except the prose of the doc comments on each method. Neither compiler knows about it. That has four consequences.

### 1. Unenforced contract: typos are silent runtime misses

In the offlinePushPlan example: if Java writes `postCreateBook` and Rust reads `postCreateBooks`, nothing complains at build time; `.optString(...)` returns the empty string and the plan silently does the wrong thing. If a new `action` value is added on the Rust side but Java's switch has no matching case, it falls through silently. That `action` string is effectively an enum, but with no enum type on either side an invalid value is a runtime branch-miss instead of a non-exhaustive-match error.

This is not hypothetical. Before the Phase 0 hardening, error replies were bare `{"error": "<message>"}`, and several decisions substring-matched the message prose (the CardDAV If-Match 412 retry, delete convergence on 404, token refresh on 401, Google's etag retry on 400); a reworded upstream message would silently disable them. The fix was to add a typed `status` field (`BridgeError { error, status }` mirrored by `CardamumException.status`) so the decisions branch on the number, not the prose. That fix is the typing direction applied to one struct; the plan generalizes it. The R8 keep-rule bug that broke every release build with a JNI method-not-found is a cousin from the same family: an unenforced, string-specified contract failing only at runtime, only in the minified build.

### 2. Duplicated field names, drift

About 60 key-string literals exist twice, once on each side: `id`/`uri`/`etag`/`vcard`/`books` for a card, `ref`/`op`/`id`/`vcard`/`baseVcard`/`add`/`remove` for a push change, `access_token`/`refresh_token`/`expires_in` for tokens, and so on. syncCards even re-parses its CardDelta reply field by field by hand instead of symmetrically. Two hand-maintained copies of the same schema is a standing invitation to drift.

### 3. A whole layer of stringly-typed dispatch

Beyond the JSON keys, the operations are strings too: `accountBase(kind)` switches on the sentinels `googleCarddav`/`google`/`msgraph`/`jmap`; `cardTypeOrder(kind)` and `formEntry(kind)` on five kinds; the offline `action` result and the `op` fields on their own value sets. The account base URL carries a fake scheme (`google://`, `jmap://`) that `Backend::of` string-matches, and accountInfo exists purely so Java can re-derive the backend and account-level back out of that opaque string, memoize it in a ConcurrentHashMap, and poke it with `optString("backend")` in five predicate methods.

### 4. Chatty and boilerplate-heavy

The same untyped field-model JSON crosses the boundary three or four times per edit as it goes projectCard to formView to formEntry to applyCard, re-serialized and re-parsed each hop. And roughly 90% of every ffi.rs export is the identical decode-args then encode-json shell around a one-line delegate.

## Why it is worth solving

The payoff is turning a class of runtime-only, often silent bugs into compile-time ones, killing the duplicated schema, shrinking the surface, and making the bridge cheaper to extend: adding a field or a method becomes a typed change the compiler checks, not a coordinated edit to two hand-written parsers plus a prose comment. It is the maintainability risk that will bite first as the bridge grows.

The crucial enabling fact: this is not speculative, because offline.rs already does it. Its driver-seam replies are fully typed serde structs and tagged enums today (`PlacementJson`, `BaseJson`, `WriteOpJson`, `ChangeJson`, `MutationJson`, `StatusJson` with `#[serde(tag = "op")]`). So the plan is really "make store.rs and project.rs look like offline.rs," a known-good pattern already in the tree.

## How: three levers

### Lever C: narrow the surface first

Fewer and coarser crossings mean less to type later, and some of the worst untyped surface disappears outright:

- Fold the five accountInfo predicates into account and base-URL construction, so the backend and account-level are decided once at load instead of re-derived from the sentinel string on every call (this also deletes the memo map).
- Coarsen the eight per-row offline plan verbs. Each is one row of facts to one decision, and they feed store.rs's 37-accessor facts blob, the single largest untyped hotspot; pushing that per-row planning into the offlineSync engine drive means Java never assembles the blob at all.
- Hold one typed FieldModel across the editor instead of shuttling the same untyped model JSON three or four times.

Do this first because typing a smaller, tighter surface is cheaper, and the facts blob is the ugliest part.

### Lever A: type the replies

Promote every `json!` reply in store.rs and project.rs to a named `#[derive(Serialize, Deserialize)]` struct, mirroring offline.rs, and write the matching Java model class for each. Turn the stringly dispatch keys (kind, action, op, the sentinel) into serde-renamed enums so an invalid value is a type error. Make syncCards symmetric through CardDelta on both sides. Roughly 141 `json!` sites collapse to maybe 30 to 40 structs plus about 30 Java DTOs. This is where the runtime-key surface and the duplicated literals die. The honest cost: two hand-maintained copies (a Rust struct and a Java class) that can still drift until lever B.

### Lever B: generate the Java side (optional, later)

Once lever A has removed every `serde_json::Value` return (those are what block any generator, since a bag of Value has no schema to read), generate the Java DTOs from the Rust serde types so drift becomes a compile error instead of a runtime one. Two realistic paths: serde-reflection with serde-generate, which already targets Java and understands serde, or schemars to emit a JSON Schema plus jsonschema2pojo to emit the classes, wired into a build step. High upfront cost (toolchain and CI), near-zero marginal cost per new method afterward. It is optional because it only pays off at scale, and lever A is a hard prerequisite.

## What it does not do

This does not switch to a binary or strongly-typed JNI ABI. The boundary stays all-JSON-strings, so there is no transport change and no risk to the transport layer. The plan is narrowly about making the JSON schema a single typed source of truth rather than a prose contract re-implemented by hand on both sides. It meets the hub decomposition at ffi.rs: because that file is now split into per-domain modules (ffi/card.rs, ffi/offline.rs, ffi/project.rs), the typed replies land cleanly inside the right module when the time comes.
