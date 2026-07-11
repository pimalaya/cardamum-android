# Performance notes

Living notes on sync throughput and surprising counts. Nothing here is a correctness or data-loss issue; it is speed and things that look off and want a closer look later.

## Per-contact pushes (no batching)

The io-offline engine yields one push change per placement, so the client applies creates, updates and deletes one contact at a time. Importing 150 contacts to Google People issues ~150 sequential people.createContact calls, which is why an import crawls.

What each backend could do better:

- Google People: io-google-people already exposes the batch verbs (people.batchCreateContacts, up to 200 per call, plus batchUpdateContacts and batchDeleteContacts), but the sync does not use them. The engine drives per-placement pushes through create_google_card / update_google_card, which wrap a single PeopleContactCreate / PeopleContactUpdate. Batching would need the adapter to group a round's push changes into one batch call.
- CardDAV: no native batch. Each vCard is its own PUT (and each delete its own DELETE on the href), so the only lever is concurrency (parallel requests), not one call.
- JMAP: Contact/set is inherently multi-object (many creates/updates/destroys in one request), so JMAP could be one round trip; but the per-placement driving currently serializes it into one Contact/set per contact.

Direction: give the io-offline push seam the whole set of changes for a collection round, so backends with a batch verb (People, JMAP) issue one call and CardDAV parallelizes. Until then, an import is O(n) sequential requests.

## Full push count when disabling phone mirroring

Observed: unchecking a book's local (phone) mirror still runs a sync for a while and reports ~150 pushes at the end, with nothing edited. No data loss, just an unexpected full-set push.

Hypotheses to confirm with a trace:

- setBookState now clears the phone axis for the book when mirroring goes off (the data-loss fix, see docs/phone-sync-plan.md). A sync right after sees every card as not-yet-projected on the phone and may re-touch the whole set before the spoke is torn down.
- The reconcile that removes the Android account can race a syncPhone pass that still projects the full set once before the account is gone.
- The count may be phone-axis writes reported as pushes rather than server writes. Which spoke the 150 hit matters: a phone round is only local (cheap, cosmetic), a server round would be real remote writes and the actual concern.

Next step: re-add the syncPhone and server push counters to the log, capture one toggle-off cycle, and confirm which spoke the pushes hit and why the set is full rather than empty.
