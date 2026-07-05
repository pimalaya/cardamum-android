# Android contacts to vCard mapping

The contract for the phone projection layer: which ContactsContract data kinds map to which vCard properties, in both directions. Target format is vCard 3.0 (the CardDAV baseline, RFC 6352 requires it); vCard 4.0 differences are noted where they matter.

## Architecture

Three representations, two converters:

```
ContactsContract rows  <-- Java -->  neutral field model  <-- Rust (vcard-rs) -->  vCard document
```

- **Java** reads and writes ContactsContract (the only side that can) and converts rows to and from a neutral field model (a flat JSON-friendly structure mirroring the tables below). The in-app edit form speaks the same model.
- **Rust** owns the vCard side (rust/src/project.rs): `project` maps a vCard onto the field model, and `apply` patches an edited model back onto the stored vCard as a **patch** through the vcard-rs CST, preserving everything the model cannot represent (unknown properties, parameters, property groups, ordering, folding). Both the edit form and, later, the phone-edit merge go through `apply`, so there is exactly one write path onto a vCard.

The base vCard in CardStore is the document of record; the phone is a lossy projection of it. A phone edit never regenerates the vCard, it edits the base document.

## Custom property policy

Custom X-* names are arbitrary; two clients inventing their own spellings for the same data is how duplicates are born. Cardamum therefore syncs the least common denominator and applies Postel's law to the rest:

1. **Never mint X-* properties.** New phone-side data is written only to well-defined vCard properties. A phone field with no standard slot does not sync: it stays on the phone contact, unharmed and unpropagated.
2. **Recognize common X-* conventions on read.** Properties that iOS, DAVx5 and Google CardDAV widely write (grouped item.X-ABLabel labels, X-PHONETIC-*, X-ANNIVERSARY, X-ABDATE, X-ABRELATEDNAMES, legacy X-AIM-style IM handles) are projected onto the phone when present, so data from the user's other devices is visible and never re-created under a second name.
3. **Edit in place, never rename.** An edit to data that arrived through an X-* property patches that same property; Cardamum only ever edits custom names other clients chose, it never introduces a competing one.

The practical cost under vCard 3.0: custom labels, phonetic names, anniversaries and year-less birthdays entered on the Android side stay phone-local. vCard 4.0 standardizes most of these (ANNIVERSARY, RELATED, PREF=1, truncated `--MMDD` dates), so when a server advertises 4.0 in its supported-address-data, the synced set grows without any custom property.

## Raw contact level

One raw contact per vCard, owned by the addressbook's Android account.

| RawContacts column | Maps to | Notes |
|---|---|---|
| SOURCE_ID | card resource id | last path segment without .vcf |
| SYNC1 | ETag | informational; the ETag is the remote spoke's revision marker, not the phone's |
| SYNC2 | vCard UID | UID and resource id can differ |
| SYNC4 | vCard content hash | the phone spoke's revision token: projection skips a contact only when this hash is unchanged, so staged local edits project before they are pushed |
| DIRTY | local-edit marker | set by the platform on user edits; our own writes go through CALLER_IS_SYNCADAPTER and never set it |
| DELETED | local-delete marker | tombstone until the sync pushes the DELETE |
| STARRED | not mapped | no vCard equivalent; stays local |

## Data kinds

| ContactsContract kind | vCard property | Direction notes |
|---|---|---|
| StructuredName | N (family;given;middle;prefix;suffix) + FN (display name) | FN is mandatory in vCard; when the phone has no display name, compose it from N. ContactsProvider derives the visible name from the structured components whenever present (DISPLAY_NAME is ignored), so when FN diverges from the natural join of N's parts, the projection writes FN alone: the vCard's formatted name always wins on the phone. Phonetic given/middle/family are read from the X-PHONETIC-FIRST/MIDDLE/LAST-NAME conventions when present; phonetics entered on the phone stay phone-local |
| Nickname | NICKNAME | comma-separated multi-value on the vCard side |
| Phone | TEL;TYPE=... | see the phone type table below |
| Email | EMAIL;TYPE=... | HOME and WORK map to TYPE; OTHER, MOBILE and CUSTOM are a bare EMAIL (X-MOBILE and X-ABLabel recognized on read) |
| StructuredPostal | ADR (pobox;ext;street;locality;region;code;country) | STREET street, POBOX pobox, NEIGHBORHOOD ext, CITY locality, REGION region, POSTCODE code, COUNTRY country; FORMATTED_ADDRESS maps to the companion LABEL property (3.0) or LABEL parameter (4.0); when only FORMATTED_ADDRESS is set, it goes to street |
| Organization | ORG (company;department) + TITLE + ROLE | TITLE from TITLE, ROLE from JOB_DESCRIPTION; OFFICE_LOCATION and SYMBOL have no vCard slot and stay phone-side lossy (preserved in the base document if the vCard carried them) |
| Im | IMPP with a URI scheme per protocol | xmpp: (JABBER, GOOGLE_TALK), skype:, aim:, msnim:, ymsgr:, icq:, qq:; on import also read the legacy X-AIM / X-ICQ / X-JABBER / X-MSN / X-YAHOO / X-SKYPE-USERNAME properties. Kind is deprecated on Android but still what contact apps show |
| Website | URL | one URL per row; TYPE via grouped X-ABLabel when custom |
| Event, TYPE_BIRTHDAY | BDAY | Android stores dates as strings, full (2001-04-12) or year-less (--04-12); 4.0 supports the truncated --0412 natively, 3.0 does not, so under 3.0 only full-date birthdays sync (the iOS X-APPLE-OMIT-YEAR convention is recognized on read, never written) |
| Event, TYPE_ANNIVERSARY | ANNIVERSARY (4.0 only) | no standard 3.0 slot, so not written under 3.0; X-ANNIVERSARY and X-ABDATE recognized on read |
| Event, other/custom | not written | X-ABDATE with its label recognized on read |
| Relation | RELATED;TYPE=... (4.0 only) | written where a standard RELATED type exists (child, parent, sibling via brother/sister, spouse, friend, kin via relative); the rest, and everything under 3.0, stays phone-local; X-ABRELATEDNAMES recognized on read |
| SipAddress | IMPP:sip:... | IMPP is well-defined (RFC 4770); kind deprecated on Android, mapped for completeness |
| Note | NOTE | multiple NOTEs concatenate into the single phone note, and stay separate properties in the base document |
| Photo | PHOTO;ENCODING=b;TYPE=JPEG (3.0), PHOTO:data:... (4.0) | the raw contact's primary photo only; Android recompresses photos on insert, so equality is byte-of-what-we-wrote, see the loop rules |
| GroupMembership | CATEGORIES | not projected: Cardamum creates no groups (the labels section of contacts apps belongs to the user); the addressbook shows up under the accounts section via the contacts-authority syncable flag instead, and CATEGORIES stay vCard-side, preserved by the patch |
| Identity | not mapped | platform-internal |
| third-party kinds (WhatsApp, ...) | not mapped | belong to other apps' accounts anyway |

## Phone types

| Android Phone.TYPE | TEL TYPE (3.0) |
|---|---|
| HOME | HOME,VOICE |
| MOBILE | CELL |
| WORK | WORK,VOICE |
| FAX_HOME / FAX_WORK / OTHER_FAX | HOME,FAX / WORK,FAX / FAX |
| PAGER / WORK_PAGER | PAGER / WORK,PAGER |
| WORK_MOBILE | WORK,CELL |
| CAR | CAR |
| ISDN | ISDN |
| OTHER | VOICE |
| MAIN / COMPANY_MAIN / ASSISTANT / CALLBACK / RADIO / TELEX / TTY_TDD / MMS / CUSTOM | VOICE; the number syncs, the exotic type or custom label stays phone-local (grouped X-ABLabel labels recognized on read, per the custom property policy) |

IS_PRIMARY / IS_SUPER_PRIMARY map to TYPE=PREF (3.0) or PREF=1 (4.0), on phones, emails and addresses alike.

## Round-trip and loop avoidance

The nightmare scenario is A-B-A churn: remote edit projected to the phone, phone write re-detected as a local edit, pushed back, new ETag, forever. The rules that prevent it:

1. **CALLER_IS_SYNCADAPTER on every write.** ContactsContract only sets DIRTY on writes that do not carry this flag. The engine's own projections never mark rows dirty, so the next sync sees no phantom local change. User edits through any contacts app do set DIRTY, which is exactly the signal wanted.
2. **Patch, never regenerate.** Phone-side changes are computed as a field-model diff (phone rows vs the base vCard projected to the field model) and applied onto the base vCard through the vcard-rs CST. Unmapped properties, parameters, groups and ordering survive untouched, so a no-op projection round-trip produces a byte-identical vCard and nothing is pushed.
3. **Diff in field space, not byte space.** Local change detection compares neutral field models (same normalization on both sides: trimmed strings, type sets compared as sets, multi-values matched by type-plus-value identity, not row order). Phone numbers are compared as entered; no digit normalization, since rewriting user input is itself an edit. Phone-local extras excluded by the custom property policy (custom labels, unsynced kinds) are not part of a row's identity: projection updates the mapped columns of an existing row and leaves the extras riding along, rather than deleting and re-inserting the row.
4. **No projected-snapshot table.** The projection of the base vCard is deterministic, so the local diff recomputes it fresh each sync instead of persisting a second copy that could drift. The one exception is the photo: Android transcodes inserted photos, so the engine stores the hash of the bytes it last wrote (raw contact SYNC3) and treats the photo as locally edited only when the current hash differs.
5. **Base advances only after both sides landed.** After pushing local changes (new ETag recorded) and projecting remote changes, the merged vCard becomes the new base in CardStore. A crash between steps re-runs an idempotent sync: pushes are guarded by If-Match, projections by rule 2's byte-identity.

## Sync data flow

Per addressbook, per Sync pass (hub and spoke, see design.md; io-offline pairwise, full enumeration):

1. Store to phone, ingest: read the phone's raw contacts, convert them to field models, diff against the projection of each card's base, and apply the diffs onto the working vCards through the CST patch.
2. Store to remote: three-way merge per UID between working, base and fetched vCards (vcard-rs merge); local wins push (PUT If-Match), remote wins pull, divergences keep both per the Pimalaya conflict rule. The merged state becomes the new base.
3. Store to phone, project: write the pulled and merged changes back to the mapped rows (minimal ContentProviderOperations, CALLER_IS_SYNCADAPTER).
