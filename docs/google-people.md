# Google People API backend

The fourth backend behind CardamumClient: accounts whose base URL carries the `google://<email>` sentinel route every operation through io-google-people's coroutines, with rust/src/google.rs projecting People person resources to and from the vCard document of record. The account exposes one Contacts addressbook (the People API has no folders; contact groups are m:n labels), and contacts list through `people.connections.list` paging.

Two People mechanics shape the projection:

- **Update mask.** `people.updateContact` fully replaces every field named in `updatePersonFields`, and a masked field absent from the body is cleared. The projection therefore keeps a fixed set of managed fields (`google::MANAGED_FIELDS`) and sends full-state bodies: absent vCard properties clear their People field, while everything outside the mask (memberships, photos, events, ...) survives untouched on the server. With a base vCard the mask shrinks further to the fields the edit changed.
- **Etag guard.** People requires the person's current `etag` in every update body and rejects stale ones, so unlike the Graph and JMAP backends updates are not last-write-wins. The person etag is the card's ETag in the store; when unknown, the bridge fetches it before patching.

## vCard 4.0 to People mapping

Every property of RFC 6350 §6, plus the registered extensions worth a row. **Bold** = implemented in rust/src/google.rs today; *candidate* = a People slot exists but is not wired yet; no slot = nothing on the People side.

| vCard 4.0 | Google People |
|---|---|
| `BEGIN` / `END` / `VERSION` | structural, nothing to map |
| `SOURCE` | no slot |
| `KIND` | no slot; People contacts are always individual persons (groups live in the separate contactGroups resource) |
| `XML` | no slot |
| `FN` | **`names[0].unstructuredName`** on write; reads prefer the server-formatted `names[0].displayName`, which is output-only |
| `N` | **`names[0]`**: `familyName`, `givenName`, `middleName`, `honorificPrefix`, `honorificSuffix` |
| `NICKNAME` | **`nicknames[].value`**, one People entry per value |
| `PHOTO` | *candidate*: `photos[].url` is output-only in the person body; writing goes through the dedicated `people.updateContactPhoto` method |
| `BDAY` | **`birthdays[0].date`** (`year`/`month`/`day`); full dates only both ways today. People's partial dates could map to 4.0 truncated `--MMDD` later; the free-text `birthdays[].text` has no slot |
| `ANNIVERSARY` | *candidate*: `events[]` with type `anniversary` |
| `GENDER` | *candidate*: `genders[].value` (`male`/`female`/`unspecified`) plus `addressMeAs`; vCard's sex component (M/F/O/N/U) is richer, so the mapping is lossy |
| `ADR` | **`addresses[]`**: `poBox`, `extendedAddress`, `streetAddress` (street components join with newlines, and back), `city`, `region`, `postalCode`, `country`; `TYPE=home/work` ↔ `type` |
| `TEL` | **`phoneNumbers[].value`** + `type`: `cell` ↔ `mobile`, `home` ↔ `home`, `work` ↔ `work`; other People types (`homeFax`, `pager`, `googleVoice`, ...) keep the number, drop the type |
| `EMAIL` | **`emailAddresses[].value`** + `type` (`home`/`work`) |
| `IMPP` | **`imClients[].protocol` + `.username`**: the URI scheme becomes the protocol (`xmpp:jane@x` → protocol `xmpp`); *candidate refinement*: route `sip:` URIs to `sipAddresses[]` instead |
| `LANG` | *candidate*: `locales[].value` (BCP 47 tags on both sides) |
| `TZ` | no slot |
| `GEO` | no slot |
| `TITLE` | **`organizations[0].title`** (a TITLE without ORG still creates the organization entry) |
| `ROLE` | **`occupations[0].value`** |
| `LOGO` | no slot |
| `ORG` | **`organizations[0].name` + `.department`**: first component is the name, the rest joins into the department |
| `MEMBER` | no slot (only meaningful on `KIND:group` vCards; People groups are not person fields) |
| `RELATED` | **`relations[].person` + `.type`** for `spouse` and `child` (free-form `VALUE=text` names only); *candidate* to widen: People also speaks `mother`, `father`, `parent`, `brother`, `sister`, `friend`, `relative`, `domesticPartner`, `manager`, `assistant`, `partner` (vCard `sibling` and `kin` map lossily) |
| `CATEGORIES` | no mapping: contact group memberships are the card's addressbook memberships ([merged-view.md](merged-view.md)), surfaced structurally rather than as vCard data |
| `NOTE` | **`biographies[0].value`** with `contentType: TEXT_PLAIN`; multiple NOTEs join with newlines into one biography, HTML biographies (from Google profiles) are skipped on read rather than mangled |
| `PRODID` | no slot |
| `REV` | read-only *candidate*: `metadata.sources[].updateTime` (output-only) |
| `SOUND` | no slot |
| `UID` | **`resourceName`** minus the `people/` prefix; read direction only: it is the addressing key, never written back |
| `CLIENTPIDMAP` | no slot |
| `URL` | **`urls[].value`** (URL types not mapped) |
| `KEY` | no slot |
| `FBURL` | *candidate*: `calendarUrls[]` with type `freeBusy` |
| `CALADRURI` | no slot |
| `CALURI` | *candidate*: `calendarUrls[].url` (`home`/`work` types) |
| `EXPERTISE` (RFC 6715) | *candidate*: `skills[].value` |
| `HOBBY` (RFC 6715) | *candidate*: `interests[].value` (lossy: People has one interests list for both HOBBY and INTEREST) |
| `INTEREST` (RFC 6715) | *candidate*: `interests[].value` |
| `ORG-DIRECTORY` (RFC 6715) | no slot |
| `BIRTHPLACE` / `DEATHPLACE` / `DEATHDATE` (RFC 6474) | no slot |
| `CONTACT-URI` (RFC 8605) | no slot |
| `SORT-AS` parameter on N | *candidate*: `fileAses[].value` (vCard has no file-as property; the SORT-AS parameter is the standard sort slot) |
| `X-PHONETIC-*` conventions (or RFC 9554 PHONETIC) | *candidate*: `names[0].phoneticFamilyName` / `phoneticGivenName` / `phoneticMiddleName` / `phoneticFullName` |
| `X-*` | no native slot; see the round-trip design below |

## People fields with no vCard slot

The reverse view: person fields the projection leaves unmanaged today, and where each one could land. Everything here already survives Cardamum updates on Google itself (it stays out of the update mask); the destinations below only matter for making the data visible in the vCard document of record, and portable when a card moves to another addressbook.

| Google People | Destination |
|---|---|
| `memberships` | structural: contact groups are the card's addressbook memberships ([merged-view.md](merged-view.md)), not vCard data |
| `photos`, `coverPhotos` | `PHOTO` (portable); upload via `updateContactPhoto` |
| `events` | `ANNIVERSARY` for type `anniversary` (portable); the X-ABDATE convention for other dated events |
| `genders` | `GENDER` (portable, lossy) |
| `locales` | `LANG` (portable) |
| `interests` | `INTEREST` / `HOBBY` (portable, RFC 6715) |
| `skills` | `EXPERTISE` (portable, RFC 6715) |
| `sipAddresses` | `IMPP:sip:...` (portable) |
| `calendarUrls` | `CALURI` / `FBURL` (portable) |
| `fileAses` | `SORT-AS` parameter on N (portable) |
| `names[].phonetic*` | X-PHONETIC-* conventions, or RFC 9554 PHONETIC parameters (portable-ish) |
| `locations` | Google-scoped: X-GOOGLE-LOCATION |
| `externalIds` | Google-scoped: X-GOOGLE-EXTERNAL-ID (values reference the source system, meaningless elsewhere) |
| `miscKeywords` | Google-scoped (Outlook-inherited keyword types): X-GOOGLE-MISC-KEYWORD |
| `userDefined` | an X- property per key (they are the user-visible custom fields of the Google Contacts UI) |
| `clientData` | reserved: this is where Cardamum would stash vCard X-* properties (below) |
| `ageRanges`, `metadata` | output-only, nothing to write back |

## Round-tripping vCard X-* through Google

Yes, People can carry unknown vCard data. The person resource has two free-form key/value list fields:

- **`clientData`**: arbitrary data "populated by clients", invisible in every Google UI. This is the designed slot for machine round-trip data: Cardamum would store each unmanaged X-* property line (name, parameters, value) under a `clientData` entry and restore it on read, making the Google backend X-*-preserving like CardDAV.
- **`userDefined`**: same shape, but rendered in the Google Contacts UI as the user's custom fields. Wrong for opaque X-* blobs (they would clutter the UI), right as the destination for X- properties that carry a human-readable label and value.

Both are writable through the normal update mask (`clientData`, `userDefined`), so no extra endpoint is involved. Quota and size limits on these fields are undocumented; the implementation should degrade gracefully (drop with a log, never fail the write) if Google rejects an oversized entry.

## Provider-scoped X-GOOGLE-* properties

The rule for projecting People-only data into the vCard, so a card moved to another addressbook (same or different provider) keeps as much as possible:

1. **Portable semantics get real vCard slots, never X-GOOGLE-*.** Anniversaries, gender, languages, interests, skills, SIP addresses, calendar URLs and file-as all have standard (or registered-extension) vCard forms; minting X-GOOGLE-ANNIVERSARY for data vCard can express would trap portable data in a vendor namespace. These are the *candidate* rows above.
2. **Google-scoped data gets X-GOOGLE-* properties.** Group membership resource names, external ids, misc keywords and location metadata only mean something to the Google account they came from. Projecting them as X-GOOGLE-* keeps them in the document of record, lets them ride a move to another addressbook as inert baggage, and restores them bit-perfect if the card ever moves back; other backends simply preserve them like any X-* (CardDAV natively, Graph and JMAP through their own unmanaged-data story).
3. **Namespace care.** Google's own CardDAV historically emits X-GOOGLE-TALK and friends, so Cardamum-minted names must not collide with them; prefixing the property set (or verifying each name against what Google CardDAV emits) is part of the implementation work.

This is a deliberate exception to the "never mint X-* properties" rule of [contacts-mapping.md](contacts-mapping.md): that rule targets phone-side user data, where competing client spellings breed duplicates. Here the minting side is a backend projection with a fixed, documented vocabulary, and the data has no standard slot by definition. The policy addendum belongs in contacts-mapping.md when this lands.

Both sections above are implemented; the cross-backend behavior (shared with Microsoft Graph) is specified in [custom-data.md](custom-data.md). The stash lands in a `clientData` entry under the key `cardamum.vcard`, stash writes merge foreign `clientData` entries under the etag guard, and external ids, misc keywords and locations ride the vCard as read-only X-GOOGLE-* properties (group memberships are structural addressbook memberships instead, per [merged-view.md](merged-view.md)). The portable graduations (the *candidate* rows of the mapping table) remain future work.
