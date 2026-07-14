# Performance notes

Living notes on sync throughput and surprising counts. Nothing here is a correctness or data-loss issue; it is speed and things that look off and want a closer look later.

## Per-contact pushes

The io-offline engine hands the driver one WantsPush yield per collection round carrying the whole change list (sync.rs collects the round's pushes before yielding), so batching is entirely a client-adapter concern; an earlier revision of this note wrongly blamed the seam. What each backend does with the round today:

- Google People: creates and deletes group into people.batchCreateContacts (200 a call) and people.batchDeleteContacts (500 a call), one write-quota unit per call, so an import of 150 contacts is one request where it used to be 150 sequential people.createContact calls (which also brushed the 90-writes-per-minute quota). Updates stay per-card on purpose: batchUpdateContacts shares one updateMask across the whole batch (a clientData-carrying card would clobber the others' stash handling), needs a people.get-flavoured etag per person (the list etag the engine holds is rejected, see the per-card retry in update_google_card), and fails as a unit, so per-card guarded updates with the existing etag-gap retry stay the safer shape. Post-cascade-fix, mass update rounds should no longer occur anyway.
- CardDAV: no batch verb exists in the standards (RFC 6352 batches reads only via addressbook-multiget; RFC 5995's POST add-member is still one resource per request; Apple's CalendarServer bulk POST extension died unstandardized and unimplemented elsewhere), so the round parallelizes instead: up to 4 pushes in flight, each its own guarded PUT or DELETE on its own connection, failures and 412 handling unchanged per request.
- JMAP: the whole round rides ContactCard/set calls (50 changes each): creates, content updates, membership patches and destroys share one request, and the per-object response maps back to per-change results, so a rejected object reports rejected instead of failing the round.
- Graph: the round rides $batch calls (20 inner requests each, the endpoint's hard limit), creates as POST, updates as delta-trimmed PATCH, deletes as DELETE, with per-item statuses mapping back likewise (a 404 on a delete reads as already converged).

## Full push count when disabling phone mirroring

Observed: unchecking a book's local (phone) mirror still runs a sync for a while and reports ~150 pushes at the end, with nothing edited. No data loss, just an unexpected full-set push.

Explanation (confirmed by reading the axis code): setBookState clears the phone axis when mirroring goes off (the data-loss fix, see docs/phone-sync-plan.md), so a phone pass that still runs before the spoke tears down sees every card as never-projected (status created) and re-adds the full set; the pushes hit the phone axis only (local, cheap), not the server. The remaining polish would be skipping the phone passes as soon as the mirror flag is off rather than while the Android account still exists.
