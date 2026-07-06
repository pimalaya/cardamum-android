# Custom data round-trip

How the non-vCard backends (Google People, Microsoft Graph) preserve the vCard data they cannot express natively, and how their provider-only data rides the vCard document of record. CardDAV needs none of this (the server stores the vCard itself), and JMAP gets it from RFC 9555: calcard stashes unconverted vCard properties into the JSContact `vCard`/`convertedProperties` properties on import and restores them on export, so preservation there rests on the server persisting those JSContact properties.

## The shared model

On push, the vCard splits three ways:

1. **Managed properties** project into native provider fields (the mapping tables of each backend entry).
2. **Own minted properties for this provider** (X-GOOGLE-* on a Google account, X-MSGRAPH-* on a Graph account) are consumed and dropped: the server-side native field is authoritative, so they never echo back as data. They are read-only projections; editing them locally does not push, and the next fetch re-mints them from server truth.
3. **The remainder** is everything else: X-* properties, another provider's minted properties, standard properties with no slot on this provider (GENDER, KEY, partial birthdays, a fourth email past a Graph slot, ...). It is stashed verbatim in the provider's custom-data slot as raw logical property lines, joined with newlines, in original order.

On fetch, the vCard document of record is rebuilt as the projection of the native fields, plus the minted provider-scoped properties, plus the stash restored byte for byte. The invariants:

- Fetch, push, fetch is idempotent: the stash participates in change detection, so a no-op edit sends nothing.
- Lines longer than 8 KiB (base64 PHOTO blobs, essentially) are not stashed: they stay in the local document of record only, rather than risking the whole write against undocumented provider size limits.
- A card moved across providers just works: X-GOOGLE-* arriving at a Graph account is remainder there, gets stashed in Graph's slot, and restores bit-perfect if the card ever moves back.

## Google People

- **Slot**: one `clientData` entry under the key `cardamum.vcard`. `clientData` is invisible in every Google UI and designed for API clients (`userDefined` is its UI-visible sibling, wrong for opaque property lines).
- **Write**: `clientData` belongs to the managed field set, but a masked People update replaces the whole list and other clients may own entries there. So a stash write is read-merge-write: fetch the person's current `clientData`, keep the foreign entries, replace ours. This is safe precisely because of the People etag guard: if anything changed between the read and the write, the whole update is rejected, so foreign entries can never be silently clobbered. When the stash did not change, the mask excludes `clientData` and no merge fetch happens.
- **Minted**: `externalIds`, `miscKeywords` and `locations` become X-GOOGLE-EXTERNAL-ID (TYPE parameter from the id type), X-GOOGLE-MISC-KEYWORD (TYPE from the keyword type) and X-GOOGLE-LOCATION. These values only mean something to the account they came from. Group `memberships` are NOT minted: they are the card's addressbook memberships (merged-view.md), surfaced structurally on the JNI card; X-GOOGLE-MEMBERSHIP stays consumed so lines from earlier projections drop instead of stashing. Namespace care: Google's own CardDAV emits X-GOOGLE-TALK, so minted names must stay clear of Google's own vocabulary.

## Microsoft Graph

- **Slot**: one single-value extended MAPI property, id `String {c8e5e5cf-3f6c-4f0a-9d4e-52f1e7b2a9d3} Name cardamum-vcard`. Unlike open extensions, extended properties ride inline in the same POST/PATCH as the contact and read back through `$expand=singleValueExtendedProperties($filter=id eq '...')`, keeping every operation a single round trip.
- **Write**: Graph PATCH is sparse (only what is in the body changes), so there is no list-replacement hazard and no merge dance; foreign extended properties are untouched by construction. The full-state projection always carries the stash entry (empty value when the card has no remainder) so the delta projection can tell a cleared stash (PATCH an empty value) from an unchanged one (leave it out of the body); creates drop the empty entry. No etag guard exists on Graph, so the stash inherits the backend's last-write-wins semantics.
- **Read caveat**: create and update responses cannot `$expand`, so the bridge re-attaches the stash it just sent before projecting the returned card. The `$expand` clause on the contacts listing is the one Graph behavior to watch on-device; if it misbehaves, listings degrade to stash-less projections and the per-card read path stays authoritative.
- **Minted**: `fileAs`, `officeLocation`, `assistantName` and `manager` become X-MSGRAPH-FILE-AS, X-MSGRAPH-OFFICE-LOCATION, X-MSGRAPH-ASSISTANT and X-MSGRAPH-MANAGER. The Graph fields themselves stay Unset in every body, so the server value survives; portable graduations (fileAs to the SORT-AS parameter, assistant to `RELATED;TYPE=agent`) can replace individual lines later.

## The minting rule

Portable semantics get real vCard slots, never vendor properties: anniversaries, gender, languages, interests, skills, calendar URLs and the like have standard (or registered-extension) vCard forms, and minting X-GOOGLE-ANNIVERSARY for data vCard can express would trap portable data in a vendor namespace. Vendor X- properties are reserved for genuinely provider-scoped values (group resource names, external ids, MAPI keyword types), which cross providers as inert baggage and restore bit-perfect on return.

This is a deliberate, documented exception to the "never mint X-* properties" rule of [contacts-mapping.md](contacts-mapping.md): that rule targets phone-side user data, where competing client spellings breed duplicates; here the minting side is a backend projection with a fixed vocabulary, for data that has no standard slot by definition.
